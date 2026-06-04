package arc;

import javax.sound.sampled.*;
import java.util.Arrays;

/**
 * Modified version that allows selecting a specific audio output device.
 * Use this to fix the "no sound" issue on Linux.
 */
class MusicalAdultSOMAudioOut_DeviceSelect implements AutoCloseable {
    private final AdultSOM adult;
    private final SourceDataLine line;
    private final float sampleRate;
    private final int bufferSize;
    private volatile boolean running = true;
    private double phase = 0.0;
    private double[] lastInput;
    
    // Musical state
    private int beatCounter = 0;
    private int samplesPerBeat;
    private double envelope = 0.0;
    private double envelopeTarget = 0.0;
    private double[] activeFrequencies;
    // --- Delta sonification (punctuation) ---
    private final DeltaAudioLayer deltaLayer;
    private volatile double curvDelta01 = 0.0;  // set by runner thread via updateCurvatureDelta01
    private double lastCurvDelta01 = 0.0;       // used only inside audio thread

    // Musical scales
    private static final double[] MINOR_PENTATONIC = {
        220.00, 261.63, 293.66, 329.63, 392.00,
        440.00, 523.25, 587.33, 659.25, 783.99
    };
    private final SomaticHeartbeat heartbeat;
    // ---------------------------
    // Metric-driven sonification
    // ---------------------------
    
    private final TickLayer feedbackTick = new TickLayer(); // one-shot thunk
    private final RateTickLayer two1RateTicks;  // ratchet controlled by TWO1 rate

    // Metrics fed from runner (volatile = safe cross-thread)
    private volatile double two1EffRate01 = 0.0;
    private volatile double entropy01 = 0.0;
    private volatile double churn01 = 0.0;
    private volatile double tmr01 = 0.0;
    
    
    /** One-shot low thunk: simple decaying sine. */
    private final class TickLayer {
        private double env = 0.0;
        private double phase = 0.0;

        void trigger(double intensity01) {
            double a = clamp01(intensity01);
            env = Math.max(env, a);
            phase = 0.0;
        }

        double nextSample() {
            if (env < 1e-6) return 0.0;
            // ~120 Hz thunk
            phase += 2.0 * Math.PI * 120.0 / sampleRate;
            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
            double out = Math.sin(phase) * env;
            // decay ~80ms
            env *= 0.9986; // shorter thunk

            return out;
        }
    }

    /**
     * Rate-controlled tick generator ("ratchet"):
     * two1EffRate01 -> ticks/sec in [2..24].
     */
    private final class RateTickLayer {
        private double phase = 0.0;
        private double env = 0.0;
        private double clickPhase = 0.0;

        double nextSample(double rate01) {
            double r = clamp01(rate01);
            if (r < 0.02) {
                // decay and stop
                env *= 0.9995;
                return 0.0;
            }

            double ticksPerSec = 2.0 + 22.0 * r; // 2..24
            phase += ticksPerSec / sampleRate;

            // On wrap -> trigger a short tick
            if (phase >= 1.0) {
                phase -= 1.0;
                env = Math.max(env, 0.35 + 0.65 * r);
            }

            if (env < 1e-6) return 0.0;

            clickPhase += 2.0 * Math.PI * 2000.0 / sampleRate;
            if (clickPhase > 2.0 * Math.PI) clickPhase -= 2.0 * Math.PI;

            double out = Math.sin(clickPhase) * env;
            env *= 0.9975; // faster decay so it sounds like ticks
            return out;

        }
    }

    /**
     * Constructor that allows specifying which audio device to use.
     * 
     * @param adult The AdultSOM
     * @param deviceIndex Which mixer to use (from AudioSystem.getMixerInfo())
     *                    Set to -1 to use system default
     */
    public MusicalAdultSOMAudioOut_DeviceSelect(AdultSOM adult, int deviceIndex) throws LineUnavailableException {
        this.adult = adult;
        this.sampleRate = ARCConfig.OUTPUT_SAMPLE_RATE;
        this.bufferSize = ARCConfig.OUTPUT_BUFFER_SIZE;
        this.samplesPerBeat = (int)(sampleRate * 60.0 / ARCConfig.BASE_TEMPO / 4);
        this.activeFrequencies = new double[ARCConfig.NUM_OSCILLATORS];
        this.lastInput = new double[ARCConfig.FEATURE_BINS];
        this.heartbeat = new SomaticHeartbeat(
        	    72.0,              // resting heart rate
        	    this.sampleRate
        	);
        this.deltaLayer = new DeltaAudioLayer(this.sampleRate);
        this.two1RateTicks = new RateTickLayer();


        // Initialize input
        for (int i = 0; i < lastInput.length; i++) {
            lastInput[i] = Math.random();
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        
        // Select specific device if requested
        if (deviceIndex >= 0) {
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();
            if (deviceIndex >= mixers.length) {
                throw new IllegalArgumentException("Device index " + deviceIndex + " out of range (0-" + (mixers.length-1) + ")");
            }
            
            Mixer mixer = AudioSystem.getMixer(mixers[deviceIndex]);
            System.out.println("Using audio device: " + mixers[deviceIndex].getName());
            System.out.println("  " + mixers[deviceIndex].getDescription());
            
            line = (SourceDataLine) mixer.getLine(info);
        } else {
            // Use system default
            System.out.println("Using system default audio device");
            line = (SourceDataLine) AudioSystem.getLine(info);
        }
        
        line.open(format, bufferSize);
        
        System.out.println("MusicalAdultSOMAudioOut initialized:");
        System.out.println("  Sample rate: " + sampleRate);
        System.out.println("  Buffer size: " + bufferSize);
        
        updateMusicalState();
    }
    
    /**
     * Constructor that selects device by name substring match.
     */
    public MusicalAdultSOMAudioOut_DeviceSelect(AdultSOM adult, String deviceNameSubstring) throws LineUnavailableException {
        this.adult = adult;
        this.sampleRate = ARCConfig.OUTPUT_SAMPLE_RATE;
        this.bufferSize = ARCConfig.OUTPUT_BUFFER_SIZE;
        this.samplesPerBeat = (int)(sampleRate * 60.0 / ARCConfig.BASE_TEMPO / 4);
        this.activeFrequencies = new double[ARCConfig.NUM_OSCILLATORS];
        this.lastInput = new double[ARCConfig.FEATURE_BINS];
        this.heartbeat = new SomaticHeartbeat(
        	    72.0,              // resting heart rate
        	    this.sampleRate
        	);
        this.two1RateTicks = new RateTickLayer();
        for (int i = 0; i < lastInput.length; i++) {
            lastInput[i] = Math.random();
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
     // keep the real delta layer:
     this.deltaLayer = new DeltaAudioLayer(this.sampleRate);

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        
        // Find device by name
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        Mixer targetMixer = null;
        
        for (Mixer.Info mixerInfo : mixers) {
            if (mixerInfo.getName().contains(deviceNameSubstring)) {
                targetMixer = AudioSystem.getMixer(mixerInfo);
                System.out.println("Found matching device: " + mixerInfo.getName());
                System.out.println("  " + mixerInfo.getDescription());
                break;
            }
        }
        
        if (targetMixer == null) {
            System.err.println("Could not find device matching: " + deviceNameSubstring);
            System.err.println("Available devices:");
            for (Mixer.Info m : mixers) {
                System.err.println("  - " + m.getName());
            }
            throw new LineUnavailableException("No device found matching: " + deviceNameSubstring);
        }
        
        line = (SourceDataLine) targetMixer.getLine(info);
		
        line.open(format, bufferSize);
        
        System.out.println("MusicalAdultSOMAudioOut initialized");
        updateMusicalState();
    }


    public void start() {
        System.out.println("Starting audio generation thread...");
        
        new Thread(() -> {
            try {
                line.start();
                System.out.println("Audio line started successfully!");
                
                byte[] buffer = new byte[bufferSize * 2]; // stereo = 4 bytes/sample

                int sampleCount = 0;
                
                while (running) {
                    double currentTempo = ARCConfig.BASE_TEMPO;
                    samplesPerBeat = (int)(sampleRate * 60.0 / currentTempo / 4);
                    
                    for (int i = 0; i < buffer.length / 4; i++) {
                    	if (sampleCount % samplesPerBeat == 0) {
                    	    onBeat();

                    	    // ---- delta punctuation trigger (on beat boundary only) ----
                    	    double d = curvDelta01;                 // volatile read
                    	    double rise = d - lastCurvDelta01;
                    	    lastCurvDelta01 = d;

                    	    // conservative thresholds to avoid spam
                    	    if (d > 0.35 && rise > 0.05) {
                    	        double intensity = 0.65 * d + 0.35 * (rise / 0.20); // rise normalized-ish
                    	        if (intensity > 1.0) intensity = 1.0;
                    	        if (intensity < 0.0) intensity = 0.0;
                    	        deltaLayer.trigger(intensity);
                    	    }
                    	}

                        
                        // Update envelope
                        double somaticEnergy = heartbeat.energy();

                     // Slow the attack/release dynamically
                     double attack = ARCConfig.ATTACK_RATE * (0.6 + 0.4 * somaticEnergy);
                     double release = ARCConfig.RELEASE_RATE * (0.6 + 0.4 * somaticEnergy);
                     if (heartbeat.isBeat() && somaticEnergy < 0.15) {
                         envelopeTarget = 0.0;
                     }
                     if (envelope < envelopeTarget) {
                         envelope += (envelopeTarget - envelope) * (1.0 - attack);
                     } else {
                         envelope += (envelopeTarget - envelope) * (1.0 - release);
                     }

                        
                        double value = 0.0;
                        
                        // Generate chord tones
                        int numOsc = Math.min(ARCConfig.NUM_OSCILLATORS, activeFrequencies.length);
                        for (int c = 0; c < numOsc; c++) {
                            double freq = activeFrequencies[c];
                            if (freq > 0) {
                                double t = phase / sampleRate;
                                
                                double osc = 0.0;
                                double e = entropy01; // 0..1
                                double harmBoost = 0.3 + 1.8 * e;   // 0.3..2.1
                                double fundBoost = 1.1 - 0.6 * e;   // 1.1..0.5

                                osc += (ARCConfig.FUNDAMENTAL_MIX * fundBoost) * Math.sin(2 * Math.PI * freq * t);
                                osc += (ARCConfig.HARMONIC_2_MIX * harmBoost) * Math.sin(2 * Math.PI * freq * 2 * t);
                                osc += (ARCConfig.HARMONIC_3_MIX * harmBoost) * Math.sin(2 * Math.PI * freq * 3 * t);
                                osc += (ARCConfig.HARMONIC_4_MIX * harmBoost) * Math.sin(2 * Math.PI * freq * 4 * t);
                                
                                double pan = 0.8 + 0.2 * Math.sin(c * 1.234);
                                value += osc * envelope * pan / numOsc;
                            }
                        }
                        
                        // Add vibrato
                        double vibratoDepth = ARCConfig.VIBRATO_DEPTH * (0.4 + 1.6 * tmr01); // 0.4x..2.0x
                        double vibrato = 1.0 + vibratoDepth *
                            Math.sin(2 * Math.PI * ARCConfig.VIBRATO_RATE * phase / sampleRate);
                        value *= vibrato;

                        // ---- add delta punctuation (very small mix) ----
                        
                        // -------------------------------
                        // Metric-driven layers (small mix)
                        // -------------------------------
                        // TWO1 ratchet ticks
                        value += 0.22 * two1RateTicks.nextSample(two1EffRate01);


                        // Feedback thunk (one-shot)
                        value += 0.18 * feedbackTick.nextSample();


                        // Delta curvature punctuation (you already have this mixed in your other patch)
                        value += 0.12 * deltaLayer.nextSample();

                        // Soft clipping
                        value = Math.tanh(value * 0.8);
                        
                        phase++;
                        heartbeat.tick();

                        sampleCount++;
                        
                        short sample = (short)(value * Short.MAX_VALUE * ARCConfig.OUTPUT_VOLUME);

                     // LEFT
                     buffer[4 * i]     = (byte)(sample & 0xFF);
                     buffer[4 * i + 1] = (byte)((sample >> 8) & 0xFF);

                     // RIGHT (duplicate)
                     buffer[4 * i + 2] = (byte)(sample & 0xFF);
                     buffer[4 * i + 3] = (byte)((sample >> 8) & 0xFF);

                    }
                    
                    line.write(buffer, 0, buffer.length);
                }
                
                line.drain();
                line.stop();
                System.out.println("Audio thread stopped cleanly");
                
            } catch (Exception e) {
                System.err.println("ERROR in audio generation thread:");
                e.printStackTrace();
            }
        }).start();
    }

    private void onBeat() {
        beatCounter++;
        updateMusicalState();
        
        if (beatCounter % 4 == 0) {
            envelopeTarget = 1.0;
        } else if (beatCounter % 4 == 2) {
            envelopeTarget = 0.6;
        } else {
            envelopeTarget = 0.2;
        }
        
        if (beatCounter >= ARCConfig.BEATS_PER_MEASURE) {
            beatCounter = 0;
        }
    }

    private void updateMusicalState() {
        try {
            int bmuIndex = adult.findBMU(lastInput);
            double[][] weights = adult.getMapCopy();
            
            if (bmuIndex >= weights.length) return;
            
            double[] bmuWeights = weights[bmuIndex];
            double entropy = calculateEntropy();
            
            double[] scale = MINOR_PENTATONIC;
            
            double mean = 0.0;
            for (double v : bmuWeights) mean += v;
            mean /= bmuWeights.length;
            
            int rootIdx = (int)Math.floor(mean * scale.length) % scale.length;
            
            if (activeFrequencies.length != ARCConfig.NUM_OSCILLATORS) {
                activeFrequencies = new double[ARCConfig.NUM_OSCILLATORS];
            }
            
            double[] next = new double[ARCConfig.NUM_OSCILLATORS];
            next[0] = scale[(rootIdx + 0) % scale.length];
            next[1] = scale[(rootIdx + 2) % scale.length];
            next[2] = scale[(rootIdx + 4) % scale.length];
            
            if (ARCConfig.NUM_OSCILLATORS > 3) {
                next[3] = (entropy < 0.25) ? (next[0] * 2.0) : scale[(rootIdx + 6) % scale.length];
            }
            
            for (int i = 4; i < next.length; i++) {
                next[i] = next[i % 4] * (1.0 + (i / 4));
            }
            
            activeFrequencies = next;
            
        } catch (Exception e) {
            System.err.println("Error updating musical state: " + e.getMessage());
        }
    }
    
    private double calculateEntropy() {
        double[][] weights = adult.getMapCopy();
        double totalVar = 0.0;
        int count = Math.min(8, weights.length);

        for (int i = 0; i < count; i++) {
            double mean = Arrays.stream(weights[i]).average().orElse(0.5);
            double variance = 0.0;
            for (double w : weights[i]) {
                double d = (w - mean);
                variance += d * d;
            }
            totalVar += variance / weights[i].length;
        }

        double avgVar = totalVar / Math.max(1, count);
        double normalized = avgVar / 0.083;
        return Math.max(0.0, Math.min(1.0, normalized));
    }

    public void updateInput(double[] features) {
        if (features != null && features.length == lastInput.length) {
            System.arraycopy(features, 0, lastInput, 0, lastInput.length);
        }
    }
    /**
     * Feed normalized curvature delta (0..1) from your runner/instrumentation thread.
     * This is *not* used to change music state, only to trigger percussive "learning" ticks.
     */
    public void updateCurvatureDelta01(double curvatureDelta01) {
        // Keep it bounded and thread-safe via volatile write
        double v = curvatureDelta01;
        if (v < 0.0) v = 0.0;
        if (v > 1.0) v = 1.0;
        this.curvDelta01 = v;
    }

    @Override
    public void close() {
        System.out.println("Closing audio output...");
        running = false;
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        line.close();
    }
    
    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
    /** Feed TWO1 effective rate normalized [0..1]. */
    public void updateTwo1EffRate01(double v) {
        this.two1EffRate01 = clamp01(v);
    }

    /** Feed entropy normalized [0..1]. */
    public void updateEntropy01(double v) {
        this.entropy01 = clamp01(v);
    }

    /** Optional: feed churn normalized [0..1]. */
    public void updateChurn01(double v) {
        this.churn01 = clamp01(v);
    }

    /** Optional: feed TMR normalized [0..1]. */
    public void updateTmr01(double v) {
        this.tmr01 = clamp01(v);
    }

    /** One-shot feedback sound (call when tick==1 or feedback event). */
    public void triggerFeedback(double intensity01) {
        feedbackTick.trigger(intensity01);
    }

}

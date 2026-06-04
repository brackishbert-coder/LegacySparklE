package arc;

import javax.sound.sampled.*;
import java.util.Arrays;

/**
 * Modified MusicalAdultSOMAudioOut that accepts a pre-configured SourceDataLine.
 * This allows ARCSystemRunner to select the audio device before creating this.
 */
class MusicalAdultSOMAudioOut implements AutoCloseable {
    private final AdultSOM adult;
    private final SourceDataLine line;
    private final float sampleRate;
    private final int bufferSize;
    private volatile boolean running = true;
    private double phase = 0.0;
    private double[] lastInput;
    private int debugCounter = 0;
    private int lastBmuDebug = -1;
    private int lastRootDebug = -1;
    private double[] prevFeatures = null;
    private int featDbg = 0;
    
    // Musical scales (frequencies in Hz)
    private static final double[] MINOR_PENTATONIC = {
        220.00, 261.63, 293.66, 329.63, 392.00,
        440.00, 523.25, 587.33, 659.25, 783.99
    };
    
    private static final double[] MAJOR_SCALE = {
        261.63, 293.66, 329.63, 349.23, 392.00, 440.00, 493.88, 523.25
    };
    
    private static final double[] HARMONIC_MINOR = {
        220.00, 246.94, 261.63, 293.66, 329.63, 349.23, 415.30, 440.00
    };
    
    // Rhythm and timing
    private int beatCounter = 0;
    private int samplesPerBeat;
    
    // Envelope for note articulation
    private double envelope = 0.0;
    private double envelopeTarget = 0.0;
    
    // Current musical state
    private double[] activeFrequencies;

    /**
     * Constructor that accepts a pre-configured SourceDataLine.
     * The line should already be open() but not yet started().
     */
    public MusicalAdultSOMAudioOut(AdultSOM adult, SourceDataLine line, float sampleRate, int bufferSize) {
        this.adult = adult;
        this.line = line;
        this.sampleRate = sampleRate;
        this.bufferSize = bufferSize;
        this.samplesPerBeat = (int)(sampleRate * 60.0 / ARCConfig.BASE_TEMPO / 4);
        this.activeFrequencies = new double[ARCConfig.NUM_OSCILLATORS];
        
        // Adult now has 64-dim weights (features), so lastInput should be 64-dim
        this.lastInput = new double[ARCConfig.FEATURE_BINS];
        
        // Initialize with random values
        for (int i = 0; i < lastInput.length; i++) {
            lastInput[i] = Math.random();
        }

        System.out.println("MusicalAdultSOMAudioOut initialized:");
        System.out.println("  Sample rate: " + sampleRate);
        System.out.println("  Buffer size: " + bufferSize);
        System.out.println("  Oscillators: " + activeFrequencies.length);
        System.out.println("  Input dims: " + lastInput.length);
        
        updateMusicalState();
    }

    /**
     * Legacy constructor for backward compatibility.
     * Opens audio output using system default device.
     */
    public MusicalAdultSOMAudioOut(AdultSOM adult) throws LineUnavailableException {
        this.adult = adult;
        this.sampleRate = ARCConfig.OUTPUT_SAMPLE_RATE;
        this.bufferSize = ARCConfig.OUTPUT_BUFFER_SIZE;
        this.samplesPerBeat = (int)(sampleRate * 60.0 / ARCConfig.BASE_TEMPO / 4);
        this.activeFrequencies = new double[ARCConfig.NUM_OSCILLATORS];
        this.lastInput = new double[ARCConfig.FEATURE_BINS];
        
        for (int i = 0; i < lastInput.length; i++) {
            lastInput[i] = Math.random();
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format, bufferSize);
        
        System.out.println("MusicalAdultSOMAudioOut initialized (system default device)");
        updateMusicalState();
    }

    public void start() {
        System.out.println("Starting audio generation thread...");
        
        new Thread(() -> {
            try {
                line.start();
                System.out.println("Audio line started successfully!");
                
                byte[] buffer = new byte[bufferSize];
                int sampleCount = 0;
                int bufferCount = 0;
                
                while (running) {
                    double currentTempo = ARCConfig.BASE_TEMPO;
                    samplesPerBeat = (int)(sampleRate * 60.0 / currentTempo / 4);
                    
                    for (int i = 0; i < buffer.length / 2; i++) {
                        if (sampleCount % samplesPerBeat == 0) {
                            onBeat();
                        }
                        
                        // Update envelope
                        if (envelope < envelopeTarget) {
                            envelope += (envelopeTarget - envelope) * (1.0 - ARCConfig.ATTACK_RATE);
                        } else {
                            envelope += (envelopeTarget - envelope) * (1.0 - ARCConfig.RELEASE_RATE);
                        }
                        
                        double value = 0.0;
                        
                        // Generate chord tones
                        int numOsc = Math.min(ARCConfig.NUM_OSCILLATORS, activeFrequencies.length);
                        for (int c = 0; c < numOsc; c++) {
                            double freq = activeFrequencies[c];
                            if (freq > 0) {
                                double t = phase / sampleRate;
                                
                                double osc = 0.0;
                                osc += ARCConfig.FUNDAMENTAL_MIX * Math.sin(2 * Math.PI * freq * t);
                                osc += ARCConfig.HARMONIC_2_MIX * Math.sin(2 * Math.PI * freq * 2 * t);
                                osc += ARCConfig.HARMONIC_3_MIX * Math.sin(2 * Math.PI * freq * 3 * t);
                                osc += ARCConfig.HARMONIC_4_MIX * Math.sin(2 * Math.PI * freq * 4 * t);
                                
                                double pan = 0.8 + 0.2 * Math.sin(c * 1.234);
                                value += osc * envelope * pan / numOsc;
                            }
                        }
                        
                        // Add vibrato
                        double vibrato = 1.0 + ARCConfig.VIBRATO_DEPTH * 
                            Math.sin(2 * Math.PI * ARCConfig.VIBRATO_RATE * phase / sampleRate);
                        value *= vibrato;
                        
                        // Soft clipping
                        value = Math.tanh(value * 0.8);
                        
                        phase++;
                        sampleCount++;
                        
                        short sample = (short) (value * Short.MAX_VALUE * ARCConfig.OUTPUT_VOLUME);
                        buffer[2 * i] = (byte) (sample & 0xFF);
                        buffer[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
                    }
                    
                    line.write(buffer, 0, buffer.length);
                    bufferCount++;
                    

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
        
        int beatsPerMeasure = ARCConfig.BEATS_PER_MEASURE;
        if (beatCounter % 4 == 0) {
            envelopeTarget = 1.0;
        } else if (beatCounter % 4 == 2) {
            envelopeTarget = 0.6;
        } else {
            envelopeTarget = 0.2;
        }
        
        if (beatCounter >= beatsPerMeasure) {
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
            if (entropy > 0.6) {
                scale = HARMONIC_MINOR;
            } else if (entropy < 0.3) {
                scale = MAJOR_SCALE;
            }
            
            double mean = 0.0;
            for (double v : bmuWeights) mean += v;
            mean /= bmuWeights.length;
            double avgWeight = mean;
            
            double var = 0.0;
            for (double v : bmuWeights) {
                double d = v - mean;
                var += d * d;
            }
            var /= bmuWeights.length;

            int n = bmuWeights.length;
            int band = Math.max(1, n / 6);

            double low = 0.0;
            double high = 0.0;
            for (int i = 0; i < band; i++) low += bmuWeights[i];
            for (int i = n - band; i < n; i++) high += bmuWeights[i];
            low /= band;
            high /= band;

            double tilt = high - low;
            double drive = mean + 0.35 * tilt + 0.25 * Math.sqrt(var);

            int rootIdx = (int)Math.floor(Math.abs(drive) * scale.length) % scale.length;
            double root = scale[rootIdx];

            if (activeFrequencies.length != ARCConfig.NUM_OSCILLATORS) {
                activeFrequencies = new double[ARCConfig.NUM_OSCILLATORS];
            }
            
            double[] next = new double[ARCConfig.NUM_OSCILLATORS];
            next[0] = scale[(rootIdx + 0) % scale.length];
            next[1] = scale[(rootIdx + 2) % scale.length];
            next[2] = scale[(rootIdx + 4) % scale.length];

            if (ARCConfig.NUM_OSCILLATORS > 3) {
                int idx = (rootIdx + Math.max(0, scale.length - 1)) % scale.length;
                double color = scale[idx];
                next[3] = (entropy < 0.25) ? (next[0] * 2.0) : color;
            }

            int inversion = bmuIndex % 3;
            if (inversion == 1) {
                double tmp = next[0];
                next[0] = next[1];
                next[1] = next[2];
                next[2] = tmp;
            } else if (inversion == 2) {
                double tmp = next[0];
                next[0] = next[2];
                next[2] = tmp;
            }

            for (int i = 4; i < next.length; i++) {
                next[i] = next[i % 4] * (1.0 + (i / 4));
            }

            activeFrequencies = next;

            debugCounter++;
            if (debugCounter % 20 == 0) {
                if (bmuIndex != lastBmuDebug || rootIdx != lastRootDebug) {
                    
                    lastBmuDebug = bmuIndex;
                    lastRootDebug = rootIdx;
                } else {
                    System.out.printf(
                        "MUSIC STABLE: bmu=%d rootIdx=%d avgW=%.3f entropy=%.3f%n",
                        bmuIndex, rootIdx, avgWeight, entropy
                    );
                }
            }
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
            if (prevFeatures == null) {
                prevFeatures = new double[features.length];
                System.arraycopy(features, 0, prevFeatures, 0, features.length);
            } else {
                double msd = 0.0;
                for (int i = 0; i < features.length; i++) {
                    double d = features[i] - prevFeatures[i];
                    msd += d * d;
                }
                msd /= Math.max(1, features.length);

                featDbg++;


                System.arraycopy(features, 0, prevFeatures, 0, features.length);
            }

            System.arraycopy(features, 0, lastInput, 0, lastInput.length);
        }
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
}
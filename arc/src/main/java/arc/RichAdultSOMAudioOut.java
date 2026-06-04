package arc;

import javax.sound.sampled.*;
import java.util.Arrays;

class RichAdultSOMAudioOut implements AutoCloseable {
    private final AdultSOM adult;
    private final SourceDataLine line;
    private final float sampleRate;
    private final int bufferSize;
    private volatile boolean running = true;
    private double phase = 0.0;
    private double[] lastInput = new double[64];
    
    // Smoothing for frequency changes
    private double currentFreq = 440.0;
    private double targetFreq = 440.0;
    
    public RichAdultSOMAudioOut(AdultSOM adult, float sampleRate, int bufferSize) throws LineUnavailableException {
        this.adult = adult;
        this.sampleRate = sampleRate;
        this.bufferSize = bufferSize;

        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format, bufferSize);
    }

    public void start() {
        new Thread(() -> {
            line.start();
            byte[] buffer = new byte[bufferSize];
            
            while (running) {
                // Update target frequency from AdultSOM state
                targetFreq = calculateFrequencyFromAdultSOM();
                
                for (int i = 0; i < buffer.length / 2; i++) {
                    // Smooth frequency transitions
                    currentFreq += (targetFreq - currentFreq) * 0.01;
                    
                    // Get multiple frequency components from different neurons
                    double[] components = getFrequencyComponents();
                    
                    double value = 0.0;
                    
                    // Mix multiple oscillators based on SOM state
                    for (int c = 0; c < Math.min(5, components.length); c++) {
                        double freq = components[c];
                        double amp = components[c] / (components.length * 1.5); // Normalize amplitude
                        
                        // Add harmonics and different waveforms
                        value += amp * Math.sin(phase * freq * 2 * Math.PI / sampleRate);
                        value += amp * 0.3 * Math.sin(phase * freq * 2 * Math.PI / sampleRate * 2); // 2nd harmonic
                        value += amp * 0.1 * Math.sin(phase * freq * 2 * Math.PI / sampleRate * 3); // 3rd harmonic
                    }
                    
                    // Add some texture/noise based on SOM entropy
                    double entropy = calculateEntropy();
                    value += entropy * 0.05 * (Math.random() * 2 - 1);
                    
                    // Soft clipping
                    value = Math.tanh(value * 0.5);
                    
                    phase++;
                    
                    short sample = (short) (value * Short.MAX_VALUE * 0.3); // Reduce volume
                    buffer[2 * i] = (byte) (sample & 0xFF);
                    buffer[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
                }
                
                line.write(buffer, 0, buffer.length);
            }
            line.drain();
            line.stop();
        }).start();
    }

    private double[] getFrequencyComponents() {
        int bmuIndex = adult.findBMU(lastInput);
        double[][] weights = adult.getMapCopy();
        
        // Get frequencies from BMU and neighbors
        int numComponents = Math.min(8, weights.length);
        double[] freqs = new double[numComponents];
        
        for (int i = 0; i < numComponents; i++) {
            int idx = (bmuIndex + i) % weights.length;
            double avgWeight = Arrays.stream(weights[idx]).average().orElse(0.5);
            
            // Map to musical frequencies (pentatonic scale-ish)
            double[] scale = {220, 247, 277, 330, 370, 440, 494, 554};
            int scaleIdx = (int)(avgWeight * scale.length) % scale.length;
            freqs[i] = scale[scaleIdx];
            
            // Add variation based on individual weights
            freqs[i] *= (0.9 + avgWeight * 0.2);
        }
        
        return freqs;
    }
    
    private double calculateEntropy() {
        double[][] weights = adult.getMapCopy();
        double totalVar = 0.0;
        
        for (int i = 0; i < Math.min(10, weights.length); i++) {
            double mean = Arrays.stream(weights[i]).average().orElse(0.5);
            double variance = 0.0;
            for (double w : weights[i]) {
                variance += (w - mean) * (w - mean);
            }
            totalVar += variance / weights[i].length;
        }
        
        return Math.min(1.0, totalVar);
    }

    private double calculateFrequencyFromAdultSOM() {
        int bmuIndex = adult.findBMU(lastInput);
        double[][] weights = adult.getMapCopy();
        
        if (bmuIndex >= weights.length) return 440;
        
        double[] bmuWeights = weights[bmuIndex];
        double avgWeight = Arrays.stream(bmuWeights).average().orElse(0.5);
        
        // Wider frequency range
        return 110 + avgWeight * (880 - 110);
    }

    public void updateInput(double[] input) {
        if (input.length == lastInput.length) {
            System.arraycopy(input, 0, lastInput, 0, input.length);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            Thread.sleep(100); // Let audio finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        line.close();
    }
}
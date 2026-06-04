package arc;

import javax.sound.sampled.*;
import java.util.*;
import java.io.*;

/**
 * TrulyHonestMetrics: No more lies.
 * 
 * 1. Plays test tone through speakers
 * 2. Processes through full ARC system (baby → queue → adult)
 * 3. Actually runs MusicalAdultSOMAudioOut to generate music
 * 4. Captures the audio bytes it produces
 * 5. Analyzes those bytes for frequency content
 * 6. Compares input vs output frequencies
 * 
 * This is the real deal.
 */
public class TrulyHonestMetrics {
    
    private final Random rng = new Random();
    private final List<TrueResult> results = new ArrayList<>();
    
    /**
     * Test a single frequency through the complete pipeline
     */
    public TrueResult testFrequency(double inputHz, int durationMs) throws Exception {
        System.out.printf("\n=== Testing %.2f Hz for %d ms ===\n", inputHz, durationMs);
        
        // Create ARC system
        BabySOM baby = new BabySOM(ARCConfig.BABY_NODES, ARCConfig.BABY_INPUT_DIM, rng);
        AdultSOM adult = new AdultSOM(ARCConfig.ADULT_W, ARCConfig.ADULT_H, ARCConfig.ADULT_DIM, rng);
        AnonymizedQueue<double[]> queue = new AnonymizedQueue<>(ARCConfig.QUEUE_CAPACITY, rng);
        
        int sampleRate = ARCConfig.OUTPUT_SAMPLE_RATE;
        int numSamples = (sampleRate * durationMs) / 1000;
        
        // Generate input tone
        byte[] inputTone = generateTone(inputHz, durationMs, sampleRate);
        
        // STEP 1: Play tone through speakers
        System.out.println("STEP 1: Playing input tone through speakers...");
        playTone(inputTone, sampleRate);
        System.out.println("  ✓ You heard: " + inputHz + " Hz");
        
        // STEP 2: Process through ARC pipeline
        System.out.println("STEP 2: Processing through ARC pipeline...");
        processAudio(inputTone, sampleRate, baby, adult, queue);
        System.out.println("  ✓ Processed through baby → queue → adult");
        
        // STEP 3: Run MusicalAdultSOMAudioOut and capture its output
        System.out.println("STEP 3: Running MusicalAdultSOMAudioOut...");
        byte[] musicOutput = captureMusicOutput(adult, durationMs, sampleRate);
        System.out.println("  ✓ Captured " + musicOutput.length + " bytes of music");
        
        // STEP 4: Play the music output so user can hear it
        System.out.println("STEP 4: Playing what the system learned...");
        playTone(musicOutput, sampleRate);
        System.out.println("  ✓ You heard what the system produced");
        
        // STEP 5: Analyze output frequency
        System.out.println("STEP 5: Analyzing output frequencies...");
        double[] outputFreqs = analyzeFrequencies(musicOutput, sampleRate);
        double dominantOutputHz = outputFreqs[0]; // Most prominent frequency
        
        double deviation = Math.abs(dominantOutputHz - inputHz);
        double deviationPercent = (deviation / inputHz) * 100.0;
        
        System.out.printf("\nRESULT:\n");
        System.out.printf("  Input:  %.2f Hz\n", inputHz);
        System.out.printf("  Output: %.2f Hz\n", dominantOutputHz);
        System.out.printf("  Deviation: %.2f Hz (%.1f%%)\n", deviation, deviationPercent);
        
        TrueResult result = new TrueResult(inputHz, dominantOutputHz, deviation, deviationPercent);
        results.add(result);
        
        // Pause between tests
        Thread.sleep(1000);
        
        return result;
    }
    
    /**
     * Generate sine wave tone as byte array
     */
    private byte[] generateTone(double hz, int durationMs, int sampleRate) {
        int numSamples = (sampleRate * durationMs) / 1000;
        byte[] bytes = new byte[numSamples * 2];
        
        for (int i = 0; i < numSamples; i++) {
            double t = i / (double) sampleRate;
            double value = Math.sin(2.0 * Math.PI * hz * t);
            short sample = (short) (value * Short.MAX_VALUE * 0.5);
            bytes[i * 2] = (byte) (sample & 0xFF);
            bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        
        return bytes;
    }
    
    /**
     * Play audio through speakers
     */
    private void playTone(byte[] audioBytes, int sampleRate) throws Exception {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(format);
        line.open(format);
        line.start();
        line.write(audioBytes, 0, audioBytes.length);
        line.drain();
        line.stop();
        line.close();
    }
    
    /**
     * Process audio through ARC pipeline (baby training → adult learning)
     */
    private void processAudio(byte[] audioBytes, int sampleRate, BabySOM baby, 
                             AdultSOM adult, AnonymizedQueue<double[]> queue) {
        int chunkSize = ARCConfig.INPUT_BUFFER_SIZE;
        int chunksProcessed = 0;
        
        for (int start = 0; start < audioBytes.length; start += chunkSize * 2) {
            int end = Math.min(start + chunkSize * 2, audioBytes.length);
            
            // Convert bytes to doubles
            double[] chunk = new double[(end - start) / 2];
            for (int i = 0; i < chunk.length; i++) {
                int idx = start + i * 2;
                int lo = audioBytes[idx] & 0xFF;
                int hi = audioBytes[idx + 1];
                short s = (short) ((hi << 8) | lo);
                chunk[i] = s / 32768.0;
            }
            
            // Extract features
            double[] features = FFTFeatures.spectrumBins(chunk, sampleRate, ARCConfig.FEATURE_BINS);
            
            // Train baby
            for (int epoch = 0; epoch < ARCConfig.BABY_EPOCHS; epoch++) {
                baby.train(features);
            }
            
            // Enqueue and train adult
            if (chunksProcessed % 4 == 0) {
                double[] babySnapshot = baby.getFullMapFlat();
                double[] evicted = queue.enqueue(babySnapshot);
                if (evicted != null) {
                    adult.learnFromBaby(evicted);
                }
            }
            
            chunksProcessed++;
        }
        
        System.out.println("    Processed " + chunksProcessed + " chunks");
    }
    
    /**
     * Capture actual output from MusicalAdultSOMAudioOut
     * This is the critical part - we actually run the music generator and record it
     */
    private byte[] captureMusicOutput(AdultSOM adult, int durationMs, int sampleRate) throws Exception {
        // Create a custom SourceDataLine that captures bytes instead of playing them
        CapturingSourceDataLine captureLine = new CapturingSourceDataLine();
        
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        captureLine.open(format, ARCConfig.OUTPUT_BUFFER_SIZE);
        
        // Create MusicalAdultSOMAudioOut with our capturing line
        MusicalAdultSOMAudioOut musicOut = new MusicalAdultSOMAudioOut(
            adult, captureLine, sampleRate, ARCConfig.OUTPUT_BUFFER_SIZE
        );
        
        // Start it generating music
        musicOut.start();
        
        // Let it run for the specified duration
        Thread.sleep(durationMs);
        
        // Stop and get captured bytes
        musicOut.close();
        byte[] captured = captureLine.getCapturedBytes();
        
        System.out.println("    Captured " + captured.length + " bytes from MusicalAdultSOMAudioOut");
        
        return captured;
    }
    
    /**
     * Analyze audio bytes to find dominant frequencies
     */
    private double[] analyzeFrequencies(byte[] audioBytes, int sampleRate) {
        // Convert bytes to doubles
        double[] samples = new double[audioBytes.length / 2];
        for (int i = 0; i < samples.length; i++) {
            int lo = audioBytes[i * 2] & 0xFF;
            int hi = audioBytes[i * 2 + 1];
            short s = (short) ((hi << 8) | lo);
            samples[i] = s / 32768.0;
        }
        
        // Run FFT on the samples
        double[] spectrum = FFTFeatures.spectrumBins(samples, sampleRate, ARCConfig.FEATURE_BINS);
        
        // Find peak in spectrum
        int peakBin = 0;
        double peakValue = spectrum[0];
        for (int i = 1; i < spectrum.length; i++) {
            if (spectrum[i] > peakValue) {
                peakValue = spectrum[i];
                peakBin = i;
            }
        }
        
        // Convert bin to frequency
        double nyquist = sampleRate / 2.0;
        double dominantFreq = (peakBin / (double) spectrum.length) * nyquist;
        
        return new double[] { dominantFreq };
    }
    
    /**
     * Custom SourceDataLine that captures audio instead of playing it
     */
    private static class CapturingSourceDataLine implements SourceDataLine {
        private ByteArrayOutputStream capturedData = new ByteArrayOutputStream();
        private boolean isOpen = false;
        private boolean isRunning = false;
        private AudioFormat format;
        
        public byte[] getCapturedBytes() {
            return capturedData.toByteArray();
        }
        
        @Override
        public void open() throws LineUnavailableException {
            this.isOpen = true;
        }
        
        @Override
        public void open(AudioFormat format, int bufferSize) {
            this.format = format;
            this.isOpen = true;
        }
        
        @Override
        public void open(AudioFormat format) {
            open(format, 4096);
        }
        
        @Override
        public int write(byte[] b, int off, int len) {
            if (isRunning) {
                capturedData.write(b, off, len);
            }
            return len;
        }
        
        @Override
        public void start() {
            isRunning = true;
        }
        
        @Override
        public void stop() {
            isRunning = false;
        }
        
        @Override
        public void drain() {
            // Nothing to drain when capturing
        }
        
        @Override
        public void flush() {
            capturedData.reset();
        }
        
        @Override
        public void close() {
            isOpen = false;
            isRunning = false;
        }
        
        @Override
        public boolean isOpen() {
            return isOpen;
        }
        
        @Override
        public boolean isRunning() {
            return isRunning;
        }
        
        @Override
        public boolean isActive() {
            return isRunning;
        }
        
        @Override
        public AudioFormat getFormat() {
            return format;
        }
        
        @Override
        public int getBufferSize() {
            return 4096;
        }
        
        @Override
        public int available() {
            return getBufferSize();
        }
        
        @Override
        public int getFramePosition() {
            return 0;
        }
        
        @Override
        public long getLongFramePosition() {
            return 0;
        }
        
        @Override
        public long getMicrosecondPosition() {
            return 0;
        }
        
        @Override
        public float getLevel() {
            return 0;
        }
        
        @Override
        public Line.Info getLineInfo() {
            return new DataLine.Info(SourceDataLine.class, format);
        }
        
        @Override
        public void addLineListener(LineListener listener) {}
        
        @Override
        public void removeLineListener(LineListener listener) {}
        
        @Override
        public Control getControl(Control.Type control) {
            throw new IllegalArgumentException("Unsupported control");
        }
        
        @Override
        public Control[] getControls() {
            return new Control[0];
        }
        
        @Override
        public boolean isControlSupported(Control.Type control) {
            return false;
        }
    }
    
    /**
     * Run full test suite
     */
    public void runTests(List<Double> frequencies, int durationMs) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TRULY HONEST METRICS TEST");
        System.out.println("=".repeat(80));
        System.out.println("You will HEAR each input tone.");
        System.out.println("You will HEAR what the system learned.");
        System.out.println("Duration: " + durationMs + " ms per test");
        System.out.println("Tests: " + frequencies.size());
        System.out.println("=".repeat(80));
        
        results.clear();
        long startTime = System.currentTimeMillis();
        
        try {
            for (int i = 0; i < frequencies.size(); i++) {
                System.out.printf("\n[Test %d/%d]\n", i + 1, frequencies.size());
                testFrequency(frequencies.get(i), durationMs);
            }
        } catch (Exception e) {
            System.err.println("\nError: " + e.getMessage());
            e.printStackTrace();
        }
        
        long elapsed = System.currentTimeMillis() - startTime;
        printSummary(elapsed);
    }
    
    private void printSummary(long totalTimeMs) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(80));
        
        if (results.isEmpty()) {
            System.out.println("No results.");
            return;
        }
        
        double avgDev = results.stream().mapToDouble(r -> r.deviation).average().orElse(0);
        double minDev = results.stream().mapToDouble(r -> r.deviation).min().orElse(0);
        double maxDev = results.stream().mapToDouble(r -> r.deviation).max().orElse(0);
        
        System.out.printf("Tests: %d\n", results.size());
        System.out.printf("Total time: %.1f seconds\n", totalTimeMs / 1000.0);
        System.out.printf("Avg deviation: %.2f Hz\n", avgDev);
        System.out.printf("Range: %.2f - %.2f Hz\n", minDev, maxDev);
        
        System.out.println("\nAll tests:");
        for (TrueResult r : results) {
            System.out.printf("  %.2f Hz → %.2f Hz (Δ %.2f Hz, %.1f%%)\n",
                r.inputHz, r.outputHz, r.deviation, r.deviationPercent);
        }
        
        System.out.println("\n" + "=".repeat(80));
    }
    
    public static class TrueResult {
        public final double inputHz;
        public final double outputHz;
        public final double deviation;
        public final double deviationPercent;
        
        public TrueResult(double inputHz, double outputHz, double deviation, double deviationPercent) {
            this.inputHz = inputHz;
            this.outputHz = outputHz;
            this.deviation = deviation;
            this.deviationPercent = deviationPercent;
        }
    }
    
    public static void main(String[] args) {
        TrulyHonestMetrics metrics = new TrulyHonestMetrics();
        
        // Small test set - just a few frequencies to keep it manageable
        List<Double> testFreqs = Arrays.asList(
            300.0, 440.0, 880.0, 1200.0, 2000.0, 3000.0
        );
        
        // 5 seconds per test
        metrics.runTests(testFreqs, 5000);
    }
}
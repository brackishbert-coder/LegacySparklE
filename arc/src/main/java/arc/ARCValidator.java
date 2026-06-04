package arc;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * Comprehensive validation suite for ARC system.
 * 
 * Usage:
 *   ARCValidator validator = new ARCValidator(adultSOM);
 *   validator.runQuickTest();
 *   // or
 *   validator.runFullSuite(testAudioChunks);
 */
public class ARCValidator {
    
    private final AdultSOM adult;
    private final BMUUtilizationTracker bmuTracker;
    private final List<ValidationResult> results = new ArrayList<>();
    
    public ARCValidator(AdultSOM adult) {
        this.adult = adult;
        this.bmuTracker = new BMUUtilizationTracker(adult.size());
    }
    
    /**
     * Quick sanity check - run this frequently (every minute)
     */
    public void runQuickTest() {
        System.out.println("\n=== ARC Quick Validation ===");
        results.clear();
        
        test("Weight Bounds", this::testWeightBounds);
        test("BMU Diversity", this::testBMUDiversity);
        test("Pure Tone Consistency", this::testPureTone);
        
        printSummary();
    }
    
    /**
     * Full validation suite - run this less frequently (every 5-10 minutes)
     */
    public void runFullSuite(List<double[]> audioChunks) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("ARC FULL VALIDATION SUITE");
        System.out.println("=".repeat(70));
        results.clear();
        
        // Tier 1: Sanity
        System.out.println("\n[SANITY CHECKS]");
        test("Weight Bounds", this::testWeightBounds);
        test("BMU Diversity", this::testBMUDiversity);
        test("Weight Statistics", this::testWeightStats);
        
        // Tier 2: Known Signals
        System.out.println("\n[KNOWN SIGNAL TESTS]");
        test("Pure Tone", this::testPureTone);
        test("Silence", this::testSilence);
        test("Contrast (Bass vs Treble)", this::testContrast);
        test("Chirp Smoothness", this::testChirp);
        
        // Tier 3: Real Audio
        if (audioChunks != null && !audioChunks.isEmpty()) {
            System.out.println("\n[REAL AUDIO TESTS]");
            test("Temporal Continuity", () -> testTemporalContinuity(audioChunks));
        }
        
        printSummary();
    }
    
    /**
     * Record BMU for utilization tracking
     */
    public void recordBMU(int bmu) {
        bmuTracker.record(bmu);
    }
    
    // ============================================================
    // INDIVIDUAL TESTS
    // ============================================================
    
    private ValidationResult testWeightBounds() {
        double[][] weights = adult.getMapCopy();
        int violations = 0;
        double minSeen = Double.POSITIVE_INFINITY;
        double maxSeen = Double.NEGATIVE_INFINITY;
        
        for (double[] node : weights) {
            for (double w : node) {
                if (w < 0.0 || w > 1.0) violations++;
                minSeen = Math.min(minSeen, w);
                maxSeen = Math.max(maxSeen, w);
            }
        }
        
        return new ValidationResult(violations == 0)
            .add("Violations", violations)
            .add("Min weight", String.format("%.4f", minSeen))
            .add("Max weight", String.format("%.4f", maxSeen));
    }
    
    private ValidationResult testBMUDiversity() {
        BMUStats stats = bmuTracker.getStats();
        
        boolean pass = stats.utilization > 0.4 && stats.gini < 0.85;
        
        return new ValidationResult(pass)
            .add("Active nodes", stats.activeNodes + "/" + stats.totalNodes)
            .add("Utilization", String.format("%.1f%%", stats.utilization * 100))
            .add("Gini coefficient", String.format("%.3f", stats.gini))
            .add("Most visited", stats.maxVisits)
            .add("Interpretation", pass ? "Good diversity" : "Too concentrated");
    }
    
    private ValidationResult testWeightStats() {
        double[][] weights = adult.getMapCopy();
        
        double sumMean = 0;
        double sumStd = 0;
        int count = 0;
        
        for (double[] node : weights) {
            double mean = Arrays.stream(node).average().orElse(0.5);
            double variance = 0;
            for (double w : node) {
                variance += (w - mean) * (w - mean);
            }
            double std = Math.sqrt(variance / node.length);
            
            sumMean += mean;
            sumStd += std;
            count++;
        }
        
        double avgMean = sumMean / count;
        double avgStd = sumStd / count;
        
        // Healthy map should have reasonable variation
        boolean pass = avgStd > 0.05 && avgStd < 0.4;
        
        return new ValidationResult(pass)
            .add("Avg node mean", String.format("%.4f", avgMean))
            .add("Avg node std", String.format("%.4f", avgStd))
            .add("Interpretation", pass ? "Good variation" : 
                avgStd < 0.05 ? "Too uniform" : "Too chaotic");
    }
    
    private ValidationResult testPureTone() {
        double[] tone440 = generatePureTone(440.0, 64);
        
        // Feed same signal multiple times
        int[] bmus = new int[50];
        for (int i = 0; i < 50; i++) {
            bmus[i] = adult.findBMU(tone440);
        }
        
        int mode = findMode(bmus);
        int modeCount = 0;
        for (int bmu : bmus) {
            if (bmu == mode) modeCount++;
        }
        
        double consistency = modeCount / 50.0;
        boolean pass = consistency > 0.90;
        
        return new ValidationResult(pass)
            .add("Signal", "440 Hz pure tone")
            .add("Tests", 50)
            .add("Most common BMU", mode)
            .add("Consistency", String.format("%.1f%%", consistency * 100))
            .add("Interpretation", pass ? "Stable mapping" : "Inconsistent");
    }
    
    private ValidationResult testSilence() {
        double[] silence = new double[64];
        
        int[] bmus = new int[30];
        for (int i = 0; i < 30; i++) {
            bmus[i] = adult.findBMU(silence);
        }
        
        int mode = findMode(bmus);
        int modeCount = 0;
        for (int bmu : bmus) {
            if (bmu == mode) modeCount++;
        }
        
        double consistency = modeCount / 30.0;
        
        // Check if silence BMU has low energy
        double[][] weights = adult.getMapCopy();
        double avgWeight = Arrays.stream(weights[mode]).average().orElse(0.5);
        
        boolean pass = consistency > 0.90 && avgWeight < 0.35;
        
        return new ValidationResult(pass)
            .add("Signal", "Silence")
            .add("Silence BMU", mode)
            .add("Consistency", String.format("%.1f%%", consistency * 100))
            .add("BMU avg weight", String.format("%.4f", avgWeight))
            .add("Interpretation", pass ? "Low-energy mapping" : 
                avgWeight > 0.35 ? "BMU too active for silence" : "Inconsistent");
    }
    
    private ValidationResult testContrast() {
        // Bass: low frequencies
        double[] bass = new double[64];
        for (int i = 0; i < 10; i++) bass[i] = 0.8;
        bass = normalize(bass);
        
        // Treble: high frequencies
        double[] treble = new double[64];
        for (int i = 54; i < 64; i++) treble[i] = 0.8;
        treble = normalize(treble);
        
        int bmuBass = adult.findBMU(bass);
        int bmuTreble = adult.findBMU(treble);
        
        // Calculate grid distance
        int w = adult.getWidth();
        int bassX = bmuBass % w, bassY = bmuBass / w;
        int trebleX = bmuTreble % w, trebleY = bmuTreble / w;
        
        double gridDist = Math.sqrt(
            Math.pow(trebleX - bassX, 2) + Math.pow(trebleY - bassY, 2)
        );
        
        boolean pass = gridDist >= 3.0;
        
        return new ValidationResult(pass)
            .add("Bass BMU", bmuBass + String.format(" (%d,%d)", bassX, bassY))
            .add("Treble BMU", bmuTreble + String.format(" (%d,%d)", trebleX, trebleY))
            .add("Grid distance", String.format("%.2f", gridDist))
            .add("Interpretation", pass ? "Good separation" : "Too close");
    }
    
    private ValidationResult testChirp() {
        int steps = 30;
        int[] bmus = new int[steps];
        
        // Generate frequency sweep
        for (int i = 0; i < steps; i++) {
            double t = i / (double) steps;
            double freq = 200 + t * 1500; // 200-1700 Hz
            double[] signal = generatePureTone(freq, 64);
            bmus[i] = adult.findBMU(signal);
        }
        
        // Count big jumps in BMU sequence
        int w = adult.getWidth();
        int bigJumps = 0;
        
        for (int i = 1; i < steps; i++) {
            int prev = bmus[i-1];
            int curr = bmus[i];
            
            int prevX = prev % w, prevY = prev / w;
            int currX = curr % w, currY = curr / w;
            
            int manhattanDist = Math.abs(currX - prevX) + Math.abs(currY - prevY);
            if (manhattanDist > 3) bigJumps++;
        }
        
        double smoothness = 1.0 - (bigJumps / (double)(steps - 1));
        boolean pass = smoothness > 0.65;
        
        return new ValidationResult(pass)
            .add("Signal", "200-1700 Hz sweep")
            .add("Steps", steps)
            .add("Big jumps (>3 grid)", bigJumps)
            .add("Smoothness", String.format("%.1f%%", smoothness * 100))
            .add("Interpretation", pass ? "Smooth trajectory" : "Too jerky");
    }
    
    private ValidationResult testTemporalContinuity(List<double[]> audioChunks) {
        if (audioChunks.size() < 10) {
            return new ValidationResult(false).add("Error", "Need at least 10 chunks");
        }
        
        List<Integer> bmuSequence = new ArrayList<>();
        for (double[] chunk : audioChunks) {
            double[] features = FFTFeatures.spectrumBins(chunk, 44100, 64);
            int bmu = adult.findBMU(features);
            bmuSequence.add(bmu);
        }
        
        int w = adult.getWidth();
        int smoothTransitions = 0;
        int totalTransitions = bmuSequence.size() - 1;
        
        for (int i = 1; i < bmuSequence.size(); i++) {
            int prev = bmuSequence.get(i - 1);
            int curr = bmuSequence.get(i);
            
            int prevX = prev % w, prevY = prev / w;
            int currX = curr % w, currY = curr / w;
            
            int dist = Math.abs(currX - prevX) + Math.abs(currY - prevY);
            if (dist <= 2) smoothTransitions++; // Stay or move to neighbor
        }
        
        double smoothness = smoothTransitions / (double) totalTransitions;
        boolean pass = smoothness > 0.60;
        
        return new ValidationResult(pass)
            .add("Audio chunks", audioChunks.size())
            .add("Smooth transitions", smoothTransitions + "/" + totalTransitions)
            .add("Smoothness", String.format("%.1f%%", smoothness * 100))
            .add("Interpretation", pass ? "Good continuity" : "Too jumpy");
    }
    
    // ============================================================
    // UTILITIES
    // ============================================================
    
    private void test(String name, Callable<ValidationResult> testFn) {
        System.out.printf("  %-30s ... ", name);
        try {
            ValidationResult result = testFn.call();
            result.name = name;
            results.add(result);
            
            System.out.println(result.passed ? "✓ PASS" : "✗ FAIL");
            for (String detail : result.details) {
                System.out.println("      " + detail);
            }
        } catch (Exception e) {
            System.out.println("✗ ERROR: " + e.getMessage());
            results.add(new ValidationResult(false).add("Error", e.getMessage()));
        }
    }
    
    private void printSummary() {
        long passed = results.stream().filter(r -> r.passed).count();
        int total = results.size();
        
        System.out.println("\n" + "-".repeat(50));
        System.out.printf("Result: %d/%d passed (%.1f%%)%n", 
                         passed, total, (passed * 100.0 / total));
        
        if (passed == total) {
            System.out.println("✓ ALL TESTS PASSED");
        } else {
            System.out.println("✗ FAILURES DETECTED:");
            for (ValidationResult r : results) {
                if (!r.passed) {
                    System.out.println("  - " + r.name);
                }
            }
        }
        System.out.println("-".repeat(50));
    }
    
    private static double[] generatePureTone(double freq, int bins) {
        double[] spectrum = new double[bins];
        double nyquist = 22050.0;
        int peakBin = (int) ((freq / nyquist) * bins);
        
        if (peakBin < bins) {
            spectrum[peakBin] = 1.0;
            if (peakBin * 2 < bins) spectrum[peakBin * 2] = 0.3;
            if (peakBin * 3 < bins) spectrum[peakBin * 3] = 0.1;
        }
        
        return normalize(spectrum);
    }
    
    private static double[] normalize(double[] arr) {
        double max = 0;
        for (double v : arr) max = Math.max(max, Math.abs(v));
        if (max < 1e-9) return arr;
        
        double[] out = new double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = arr[i] / max;
        }
        return out;
    }
    
    private static int findMode(int[] arr) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int v : arr) {
            counts.merge(v, 1, Integer::sum);
        }
        
        int mode = arr[0];
        int maxCount = 0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > maxCount) {
                maxCount = e.getValue();
                mode = e.getKey();
            }
        }
        return mode;
    }
    
    // ============================================================
    // HELPER CLASSES
    // ============================================================
    
    private static class ValidationResult {
        boolean passed;
        String name = "";
        List<String> details = new ArrayList<>();
        
        ValidationResult(boolean passed) {
            this.passed = passed;
        }
        
        ValidationResult add(String key, Object value) {
            details.add(key + ": " + value);
            return this;
        }
    }
    
    private static class BMUUtilizationTracker {
        private final int[] visitCounts;
        private final int mapSize;
        private int totalVisits = 0;
        
        BMUUtilizationTracker(int mapSize) {
            this.mapSize = mapSize;
            this.visitCounts = new int[mapSize];
        }
        
        void record(int bmu) {
            if (bmu >= 0 && bmu < mapSize) {
                visitCounts[bmu]++;
                totalVisits++;
            }
        }
        
        BMUStats getStats() {
            int activeNodes = 0;
            int maxVisits = 0;
            
            for (int count : visitCounts) {
                if (count > 0) activeNodes++;
                maxVisits = Math.max(maxVisits, count);
            }
            
            double utilization = activeNodes / (double) mapSize;
            double gini = calculateGini();
            
            return new BMUStats(mapSize, activeNodes, utilization, gini, maxVisits);
        }
        
        private double calculateGini() {
            if (totalVisits == 0) return 1.0;
            
            double sumOfDiffs = 0;
            for (int i = 0; i < mapSize; i++) {
                for (int j = 0; j < mapSize; j++) {
                    sumOfDiffs += Math.abs(visitCounts[i] - visitCounts[j]);
                }
            }
            
            return sumOfDiffs / (2.0 * mapSize * totalVisits);
        }
    }
    
    private static class BMUStats {
        final int totalNodes;
        final int activeNodes;
        final double utilization;
        final double gini;
        final int maxVisits;
        
        BMUStats(int total, int active, double util, double gini, int max) {
            this.totalNodes = total;
            this.activeNodes = active;
            this.utilization = util;
            this.gini = gini;
            this.maxVisits = max;
        }
    }
}

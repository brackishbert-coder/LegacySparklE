package arc;

import java.util.Arrays;

public final class BmuEntropyWindow {

    private final int K;
    private final int windowSize;
    final int[] hist;

    private int n = 0;

    public BmuEntropyWindow(int K, int windowSize) {
        this.K = K;
        this.windowSize = windowSize;
        this.hist = new int[K];
    }

    public void add(int bmu) {
        if (bmu < 0 || bmu >= K) return;
        hist[bmu]++;
        n++;
    }

    public boolean ready() {
        return n >= windowSize;
    }

    /** Shannon entropy normalized by log(K) */
    public double entropyNorm() {
        if (n <= 0) return Double.NaN;

        double H = 0.0;
        for (int c : hist) {
            if (c <= 0) continue;
            double p = c / (double) n;
            H -= p * Math.log(p);
        }
        return H / Math.log(K);
    }

    public int nonZeroBins() {
        int nz = 0;
        for (int c : hist) if (c > 0) nz++;
        return nz;
    }

    public int sampleCount() {
        return n;
    }

    public void reset() {
        Arrays.fill(hist, 0);
        n = 0;
    }

    /** DEBUG ONLY */
    public int[] snapshot() {
        return Arrays.copyOf(hist, hist.length);
    }
    public ARCSystemRunner.EntropyDebug debug() {
        int N = n;
        int nonZero = 0;
        int domIdx = -1;
        int domCount = -1;

        if (N <= 0) {
            return new ARCSystemRunner.EntropyDebug(
                    0, K, 0, -1, 0, 0.0, 0.0, 0.0, 0.0
            );
        }

        double H = 0.0;

        for (int i = 0; i < K; i++) {
            int c = hist[i];
            if (c <= 0) continue;

            nonZero++;
            if (c > domCount) {
                domCount = c;
                domIdx = i;
            }

            double p = c / (double) N;
            H -= p * Math.log(p);
        }

        double hNorm = (K >= 2) ? (H / Math.log(K)) : 0.0;
        double kEff  = Math.exp(H);
        double domP  = (domCount > 0) ? (domCount / (double) N) : 0.0;

        return new ARCSystemRunner.EntropyDebug(
                N,
                K,
                nonZero,
                domIdx,
                domCount,
                domP,
                H,
                hNorm,
                kEff
        );
    }

}

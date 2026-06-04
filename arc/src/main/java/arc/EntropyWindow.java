package arc;

public final class EntropyWindow {
    private final int K;          // number of BMUs/bins
    private final int[] counts;
    private int N;

    public EntropyWindow(int K) {
        this.K = K;
        this.counts = new int[K];
        this.N = 0;
    }

    public void addHit(int bmu) {
        if (bmu < 0 || bmu >= K) return;
        counts[bmu]++;
        N++;
    }

    /** Returns normalized entropy in [0,1]. */
    public double computeHnormAndReset(StringBuilder dbgOut) {
        if (N <= 0) {
            if (dbgOut != null) dbgOut.append("ENT N=0\n");
            return 0.0;
        }

        int nonZero = 0;
        double H = 0.0;

        int domIdx = -1, domC = 0;
        for (int i = 0; i < K; i++) {
            int c = counts[i];
            if (c <= 0) continue;
            nonZero++;
            if (c > domC) { domC = c; domIdx = i; }

            double p = (double) c / (double) N;
            H -= p * Math.log(p);
        }

        // Effective K for normalization: use nonZero (or K if you prefer strict)
        int Keff = Math.max(1, nonZero);
        double Hmax = Math.log((double) Keff);
        double Hnorm = (Hmax > 0.0) ? (H / Hmax) : 0.0;

        if (dbgOut != null) {
            double domP = (double) domC / (double) N;
            dbgOut.append(String.format(
                "ENT N=%d K=%d nonZero=%d Keff=%d rawH=%.6f Hnorm=%.6f dom=(%d:%d p=%.3f)\n",
                N, K, nonZero, Keff, H, Hnorm, domIdx, domC, domP
            ));
        }

        // reset for next window
        java.util.Arrays.fill(counts, 0);
        N = 0;

        // clamp
        if (Hnorm < 0.0) Hnorm = 0.0;
        if (Hnorm > 1.0) Hnorm = 1.0;
        return Hnorm;
    }
}

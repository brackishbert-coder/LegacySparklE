package arc;

public final class RollingBmuEntropy {
    private final int numUnits;
    private final int window;
    private final int[] ring;
    private final int[] counts;

    private int pos = 0;
    private int filled = 0;

    // optional: pseudocount prevents exact 0 if you want "bounce" in UI
    private final double alpha; // set 0.0 to allow true zeros

    public RollingBmuEntropy(int numUnits, int window, double alpha) {
        this.numUnits = numUnits;
        this.window = window;
        this.alpha = alpha;
        this.ring = new int[window];
        this.counts = new int[numUnits];
        // initialize ring with -1 so we can detect empties
        java.util.Arrays.fill(ring, -1);
    }

    public void reset() {
        java.util.Arrays.fill(ring, -1);
        java.util.Arrays.fill(counts, 0);
        pos = 0;
        filled = 0;
    }

    public void push(int bmu) {
        if (bmu < 0 || bmu >= numUnits) return; // or throw

        // remove outgoing if full
        if (filled == window) {
            int out = ring[pos];
            if (out >= 0) counts[out]--;
        } else {
            filled++;
        }

        // add incoming
        ring[pos] = bmu;
        counts[bmu]++;

        pos++;
        if (pos == window) pos = 0;
    }

    /** Raw entropy in nats. */
    public double entropy() {
        int n = filled;
        if (n <= 1) return 0.0;

        // If alpha==0 -> classic empirical entropy.
        // If alpha>0 -> Dirichlet-smoothed entropy (prevents hard 0).
        double total = n + alpha * numUnits;

        double h = 0.0;
        for (int c : counts) {
            if (c == 0 && alpha == 0.0) continue;
            double p = (c + alpha) / total;
            h -= p * Math.log(p);
        }
        return h;
    }

    /** Normalized entropy 0..1 using fixed base log(numUnits). */
    public double entropy01() {
        if (numUnits <= 1) return 0.0;
        double h = entropy();
        double denom = Math.log(numUnits);
        if (denom <= 0) return 0.0;
        double e = h / denom;
        if (e < 0) return 0.0;
        if (e > 1) return 1.0;
        return e;
    }

    public int uniqueCount() {
        int u = 0;
        for (int c : counts) if (c > 0) u++;
        return u;
    }

    public double dominance() {
        int n = filled;
        if (n <= 0) return 0.0;
        int max = 0;
        for (int c : counts) if (c > max) max = c;
        return (double) max / (double) n;
    }
}

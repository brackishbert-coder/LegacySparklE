package arc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Two1AlignedTmrAverager {

    private final int pre;
    private final int post;
    private final int minEvents;

    // Event gate: keep windows where two1EffRate >= threshold
    private final double threshold;

    /**
     * percentileGate in [0..1], e.g.
     *  - 0.75 keeps top 25% strongest TWO1 windows
     *  - 0.90 keeps top 10% strongest TWO1 windows
     */
    public Two1AlignedTmrAverager(int pre, int post, int minEvents, double percentileGate) {
        if (pre < 2 || post < 1)
            throw new IllegalArgumentException("pre >= 2 and post >= 1 required");
        if (!(percentileGate >= 0.0 && percentileGate <= 1.0))
            throw new IllegalArgumentException("percentileGate must be in [0,1]");

        this.pre = pre;
        this.post = post;
        this.minEvents = Math.max(1, minEvents);
        this.threshold = Double.NaN; // computed inside computeFrom() from windows
        this.percentileGate = percentileGate;
    }

    private final double percentileGate;

    // -----------------------------
    // Result container
    // -----------------------------
    public static final class AlignedAverageResult {
        public final double[] mean;   // baseline-subtracted mean
        public final double[] std;    // baseline-subtracted std
        public final int count;       // number of events used
        public final double thresholdUsed;

        private AlignedAverageResult(double[] mean, double[] std, int count, double thresholdUsed) {
            this.mean = mean;
            this.std = std;
            this.count = count;
            this.thresholdUsed = thresholdUsed;
        }
    }

    /** windows must be ordered oldest → newest */
    public AlignedAverageResult computeFrom(List<Two1TmrValidatorControls.Window> windows) {
        int n = windows.size();
        int L = pre + post + 1;

        if (n < (pre + post + 2)) {
            return new AlignedAverageResult(new double[L], new double[L], 0, Double.NaN);
        }

        // 1) Compute TWO1 percentile threshold from the window distribution
        double[] two1 = new double[n];
        for (int i = 0; i < n; i++) two1[i] = windows.get(i).two1EffRate;

        double thr = percentile(two1, percentileGate);

        // (Optional but highly recommended)
        // System.out.printf("[AlignedTMR] percentileGate=%.2f threshold=%.6g%n", percentileGate, thr);

        // 2) Collect event-triggered snippets
        List<double[]> snippets = new ArrayList<>();

        for (int i = pre; i < n - post; i++) {
            Two1TmrValidatorControls.Window w = windows.get(i);

            // Gate: keep only strong TWO1 windows
            if (!(w.two1EffRate >= thr)) continue;

            // Baseline = mean of pre-window TMR
            double baseline = 0.0;
            for (int k = i - pre; k < i; k++) baseline += windows.get(k).tmr;
            baseline /= pre;

            double[] snippet = new double[L];
            for (int k = -pre; k <= post; k++) {
                snippet[k + pre] = windows.get(i + k).tmr - baseline;
            }

            snippets.add(snippet);
        }

        if (snippets.size() < minEvents) {
            return new AlignedAverageResult(new double[L], new double[L], snippets.size(), thr);
        }

        AlignedAverageResult agg = aggregate(snippets);
        return new AlignedAverageResult(agg.mean, agg.std, agg.count, thr);
    }

    static double percentile(double[] x, double p) {
        if (x.length == 0) return Double.NaN;

        double[] a = x.clone();
        Arrays.sort(a);

        double idx = p * (a.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);

        if (lo == hi) return a[lo];

        double w = idx - lo;
        return a[lo] * (1.0 - w) + a[hi] * w;
    }

    private static AlignedAverageResult aggregate(List<double[]> snippets) {
        int N = snippets.size();
        int L = snippets.get(0).length;

        double[] mean = new double[L];
        double[] var  = new double[L];

        for (double[] s : snippets) {
            for (int i = 0; i < L; i++) mean[i] += s[i];
        }
        for (int i = 0; i < L; i++) mean[i] /= N;

        for (double[] s : snippets) {
            for (int i = 0; i < L; i++) {
                double d = s[i] - mean[i];
                var[i] += d * d;
            }
        }

        double[] std = new double[L];
        for (int i = 0; i < L; i++) std[i] = Math.sqrt(var[i] / Math.max(1, N - 1));

        return new AlignedAverageResult(mean, std, N, Double.NaN);
    }
}

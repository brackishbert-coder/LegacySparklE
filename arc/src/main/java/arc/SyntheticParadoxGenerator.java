package arc;

import java.util.Random;

/**
 * Generates synthetic feature vectors that induce paradox-like behavior:
 * two very different inputs that map to the same AdultSOM BMU (TWO1),
 * optionally interleaved with a "water" bridge vector.
 */
public class SyntheticParadoxGenerator {

    private final AdultSOM adult;
    private final Random rng;
    private final int dim;

    private double[] A;
    private double[] B;
    private double[] W; // water (bridge)

    private int bmu = -1;
    private long lastRefreshMs = 0L;

    // tunables
    public long refreshEveryMs = 10_000;     // recompute paradox pair periodically
    public int maxSeedTries = 5000;
    public int maxMutSteps = 80_000;
    public double mutateStep = 0.08;         // coordinate perturbation magnitude
    public double targetMsd = 0.30;          // raise/lower if TWO1 threshold differs
    public boolean includeWater = true;
    public double waterNoise = 0.02;         // small noise on bridge

    public SyntheticParadoxGenerator(AdultSOM adult, int dim, Random rng) {
        this.adult = adult;
        this.dim = dim;
        this.rng = rng;
    }

    /** Call frequently; returns next synthetic features (normalized to [0,1]). */
    public double[] next(long nowMs) {
        if (A == null || B == null || nowMs - lastRefreshMs > refreshEveryMs) {
            refresh(nowMs);
        }

        // Simple cycle: A, W, B, W, ...
        int phase = (int) ((nowMs / 250) % (includeWater ? 4 : 2)); // 250ms phase matches your enqueue interval
        if (!includeWater) return (phase == 0) ? A : B;

        switch (phase) {
            case 0: return A;
            case 1: return W;
            case 2: return B;
            default: return W;
        }
    }

    /** Computes a new (A,B) paradox pair + optional water bridge. */
    public void refresh(long nowMs) {
        lastRefreshMs = nowMs;

        // 1) pick a seed vector that yields some BMU
        double[] seed = null;
        int seedBmu = -1;

        for (int t = 0; t < maxSeedTries; t++) {
            double[] x = rand01(dim);
            int b = adult.observeBMU(x);
            if (b >= 0) { seed = x; seedBmu = b; break; }
        }

        if (seed == null) {
            // fallback: deterministic low vector
            seed = new double[dim];
            seedBmu = adult.observeBMU(seed);
        }

        // 2) set A = seed; search for B far away but same BMU
        A = seed;
        bmu = seedBmu;

        B = findFarSameBMU(A, bmu);

        // 3) build water bridge (midpoint + tiny noise)
        if (includeWater) {
            W = midpoint(A, B);
            addNoiseClamp01(W, waterNoise);
        }

        System.out.println("[SYNTH] Paradox refreshed: BMU=" + bmu
                + " msd(A,B)=" + String.format("%.4f", msd(A,B)));
    }

    /** Hill-climb: maximize MSD from A while keeping BMU fixed. */
    private double[] findFarSameBMU(double[] A, int targetBmu) {
        double[] cur = A.clone();
        double bestMsd = 0.0;
        double[] best = cur.clone();

        for (int step = 0; step < maxMutSteps; step++) {
            double[] cand = cur.clone();

            // perturb a few coordinates
            for (int k = 0; k < 3; k++) {
                int i = rng.nextInt(dim);
                cand[i] += (rng.nextBoolean() ? 1 : -1) * mutateStep * (0.5 + rng.nextDouble());
            }
            clamp01(cand);

            int b = adult.observeBMU(cand);
            if (b != targetBmu) continue;

            double d = msd(A, cand);
            if (d > bestMsd) {
                bestMsd = d;
                best = cand;
                cur = cand;

                if (bestMsd >= targetMsd) break; // success
            } else {
                // sometimes accept (prevents getting stuck)
                if (rng.nextDouble() < 0.05) cur = cand;
            }
        }

        return best;
    }

    // ---------------- helpers ----------------

    private double[] rand01(int n) {
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = rng.nextDouble();
        return x;
    }

    private static double[] midpoint(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double[] m = new double[n];
        for (int i = 0; i < n; i++) m[i] = 0.5 * (a[i] + b[i]);
        return m;
    }

    private void addNoiseClamp01(double[] x, double sigma) {
        for (int i = 0; i < x.length; i++) {
            x[i] += sigma * (rng.nextDouble() * 2 - 1);
        }
        clamp01(x);
    }

    private static void clamp01(double[] x) {
        for (int i = 0; i < x.length; i++) {
            if (x[i] < 0) x[i] = 0;
            else if (x[i] > 1) x[i] = 1;
        }
    }

    private static double msd(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double s = 0;
        for (int i = 0; i < n; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return s / Math.max(1, n);
    }
}

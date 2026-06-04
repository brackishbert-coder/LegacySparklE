package arc;

import java.util.Random;

/**
 * SyntheticParadox:
 * - Generates feature vectors in [0,1] with structured phases.
 * - Designed to create "dock-water-dock" (geometric paradox) and
 *   "phase inversion burst" (temporal paradox) patterns.
 *
 * Goal: increase probability of TWO1 (same BMU, high MSD) during adult training,
 * which is the ONLY thing that increments AdultSOM.curvature in your current code.
 */
public final class SyntheticParadox {

    private final int dim;
    private final Random rng;

    // Two anchors (far apart) that we "dock" to.
    private final double[] A;
    private final double[] B;

    // Previous output (for smoothness / inversion).
    private final double[] prev;

    // Timing
    private final long t0Ms;

    // Cycle settings (tweakable)
    private final double cycleSec = 6.0;       // full cycle length
    private final double dockSec  = 2.0;       // stable "dock" segment
    private final double waterSec = 1.5;       // high-entropy middle segment
    private final double dock2Sec = 1.0;       // dock again
    private final double invSec   = 1.5;       // temporal inversion burst

    // Strength knobs
    private final double dockJitter    = 0.010;  // small noise during dock
    private final double waterJitter   = 0.060;  // bigger noise during water
    private final double driftAmp      = 0.020;  // slow drift across dims
    private final double invFlipRateHz = 20.0;   // flips per second in inversion burst
    private final double invStrength   = 0.85;   // how strongly we invert (0..1)
    private final double invAnchor     = 0.30;   // keep some anchor so BMU can stay same

    public SyntheticParadox(int dim, long seed) {
        this.dim = dim;
        this.rng = new Random(seed);
        this.t0Ms = System.currentTimeMillis();

        this.A = new double[dim];
        this.B = new double[dim];
        this.prev = new double[dim];

        // Build two far-apart anchors in [0,1].
        // A near low, B near high, with slight per-dim randomness.
        for (int i = 0; i < dim; i++) {
            double ra = 0.10 + 0.10 * rng.nextDouble();
            double rb = 0.90 - 0.10 * rng.nextDouble();
            A[i] = clamp01(ra);
            B[i] = clamp01(rb);
            prev[i] = A[i];
        }
    }

    public double[] next(long nowMs) {
        double t = (nowMs - t0Ms) / 1000.0;
        double phase = t % cycleSec;

        double[] out = new double[dim];

        // A little global drift (slow sinusoid) so it isn't dead-repetitive.
        double drift = driftAmp * Math.sin(2.0 * Math.PI * t / 9.0);

        if (phase < dockSec) {
            // DOCK 1: stable near A
            mixInto(out, A, 1.0);
            addJitter(out, dockJitter);
            addDrift(out, drift);

        } else if (phase < dockSec + waterSec) {
            // WATER: spike entropy while staying "between" A and B.
            double u = (phase - dockSec) / waterSec;      // 0..1
            double s = smoothstep(u);

            // Move along the chord A->B but add high jitter = "water"
            lerpInto(out, A, B, s);
            addJitter(out, waterJitter);
            addDrift(out, drift);

        } else if (phase < dockSec + waterSec + dock2Sec) {
            // DOCK 2: stable near B
            mixInto(out, B, 1.0);
            addJitter(out, dockJitter);
            addDrift(out, drift);

        } else {
            // TEMPORAL PARADOX (phase inversion burst):
            // Rapidly flip an inverted version of the previous vector,
            // BUT keep an anchor component so BMU has a chance to remain the same.
            double u = (phase - (dockSec + waterSec + dock2Sec)) / invSec; // 0..1
            double gate = smoothstep(u) * (1.0 - smoothstep(u)); // bell-ish (0..~0.25..0)

            // flip toggles at invFlipRateHz
            boolean flip = ((int) Math.floor(t * invFlipRateHz)) % 2 == 0;

            for (int i = 0; i < dim; i++) {
                double inv = 1.0 - prev[i];
                double base = flip ? inv : prev[i];

                // Blend: mostly invert (invStrength), but anchor toward B (invAnchor)
                double v = (1.0 - invStrength) * prev[i] + invStrength * base;
                v = (1.0 - invAnchor) * v + invAnchor * B[i];

                // Gate the whole thing so it ramps in/out instead of a hard discontinuity
                out[i] = (1.0 - gate) * B[i] + gate * v;
            }

            // add a touch of noise so exemplars differ even when BMU repeats
            addJitter(out, 0.015);
            addDrift(out, drift);
        }

        // clamp + update prev
        for (int i = 0; i < dim; i++) {
            out[i] = clamp01(out[i]);
            prev[i] = out[i];
        }

        return out;
    }

    private void lerpInto(double[] out, double[] a, double[] b, double t) {
        double tt = clamp01(t);
        for (int i = 0; i < dim; i++) out[i] = (1.0 - tt) * a[i] + tt * b[i];
    }

    private void mixInto(double[] out, double[] src, double w) {
        double ww = clamp01(w);
        for (int i = 0; i < dim; i++) out[i] = ww * src[i];
    }

    private void addJitter(double[] v, double eps) {
        for (int i = 0; i < dim; i++) v[i] += (rng.nextDouble() - 0.5) * 2.0 * eps;
    }

    private void addDrift(double[] v, double drift) {
        // drift applied to all dims, tiny
        for (int i = 0; i < dim; i++) v[i] += drift;
    }

    private static double smoothstep(double x) {
        double t = clamp01(x);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp01(double x) {
        if (x < 0.0) return 0.0;
        if (x > 1.0) return 1.0;
        return x;
    }
}

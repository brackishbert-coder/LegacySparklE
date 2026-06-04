package arc;

import java.util.Random;

/**
 * Temporal paradox generator:
 * emits a low-entropy cycle of tokens but injects phase slips (drop/dup),
 * creating persistent sequence-alignment ambiguity.
 */
public class TemporalParadoxGenerator {
    private final Random rng;
    private final double[][] tokens;   // token vectors
    private int phase = 0;

    // tunables
    public int stepMs = 250;              // matches your cadence
    public double slipProb = 0.08;        // chance to phase-slip
    public double dupProb  = 0.50;        // if slipping: duplicate vs drop
    public boolean jitter = true;
    public double jitterAmp = 0.02;

    public TemporalParadoxGenerator(Random rng, int dim, int kTokens) {
        this.rng = rng;
        this.tokens = new double[kTokens][dim];
        for (int t = 0; t < kTokens; t++) {
            for (int i = 0; i < dim; i++) tokens[t][i] = rng.nextDouble();
        }
    }

    public double[] next(long nowMs) {
        // advance phase at stepMs boundaries (stable timing)
        int desiredPhase = (int)((nowMs / stepMs) % tokens.length);

        // introduce phase slip: duplicate or drop a phase step
        if (rng.nextDouble() < slipProb) {
            if (rng.nextDouble() < dupProb) {
                // duplicate: hold phase (do NOT advance)
                desiredPhase = phase;
            } else {
                // drop: skip ahead by 1
                desiredPhase = (desiredPhase + 1) % tokens.length;
            }
        }

        phase = desiredPhase;

        double[] out = tokens[phase].clone();
        if (jitter) addJitter(out);
        return out;
    }

    private void addJitter(double[] x) {
        for (int i = 0; i < x.length; i++) {
            x[i] += jitterAmp * (rng.nextDouble() * 2 - 1);
            if (x[i] < 0) x[i] = 0;
            else if (x[i] > 1) x[i] = 1;
        }
    }
}

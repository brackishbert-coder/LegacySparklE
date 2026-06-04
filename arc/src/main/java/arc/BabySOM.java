package arc;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

final class BabySOM {
    private final double[][] weights;        // [nodes][dim]
    private final double[] trace;            // [nodes] (eligibility trace for STDP)
    private final double[] lastActivation;   // [nodes] previous activation levels

    // STDP parameters
    private final double tauPlus = 20.0;   // LTP time constant
    private final double tauMinus = 20.0;  // LTD time constant
    private final double aPlus = 0.01;     // LTP amplitude
    private final double aMinus = 0.01;    // LTD amplitude

    private int lastWinner = 0;
    private double timeStep = 0.0;
    private double[] lastInputForTwo1 = null;

    public BabySOM(int nodes, int dim, Random rng) {
        if (nodes <= 0 || dim <= 0) throw new IllegalArgumentException("nodes and dim must be > 0");
        this.weights = new double[nodes][dim];
        this.trace = new double[nodes];
        this.lastActivation = new double[nodes];

        for (int i = 0; i < nodes; i++) {
            for (int j = 0; j < dim; j++) {
                weights[i][j] = rng.nextDouble(); // [0,1)
            }
        }
    }

    /**
     * Initialize this BabySOM's weights from a flat array in node-major order:
     * flat.length must equal nodes * dim.
     *
     * This is used for the entropy-trigger feedback baby so it inherits the folded
     * adult pattern rather than random initialization.
     */
    public void initializeFromFlat(double[] flat) {
        Objects.requireNonNull(flat, "flat");

        int nodes = weights.length;
        int dim = weights[0].length;

        if (flat.length != nodes * dim) {
            throw new IllegalArgumentException(
                "Flat map size mismatch: got " + flat.length + " expected " + (nodes * dim)
            );
        }

        int k = 0;
        for (int i = 0; i < nodes; i++) {
            for (int j = 0; j < dim; j++) {
                double v = flat[k++];
                // keep bounded [0,1] consistent with system invariants
                weights[i][j] = Math.max(0.0, Math.min(1.0, v));
            }
        }

        // Reset temporal/STDP state so the inherited map starts "clean"
        Arrays.fill(trace, 0.0);
        Arrays.fill(lastActivation, 0.0);
        lastWinner = 0;
        timeStep = 0.0;
    }

    /**
     * Train using STDP learning rule.
     * Implements spike-timing-dependent plasticity where:
     * - Recent winners get strengthened (pre before post)
     * - Older activations get weakened (post before pre)
     */
    public void train(double[] x) {
        Objects.requireNonNull(x, "x");
        if (x.length != weights[0].length) {
            throw new IllegalArgumentException("Input dim mismatch: got " + x.length + " expected " + weights[0].length);
        }

        timeStep += 1.0;

        // Calculate current activations (inverse of distance)
        double[] activation = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            activation[i] = 1.0 / (1.0 + dist2(x, i));
        }

        // Find BMU
        int w = bmu(x);

     // TWO1 detection (same winner, divergent consecutive input)
     boolean isTwo1 = false;
     double[] xUsed = x;

     if (lastInputForTwo1 != null && lastWinner == w) {
         double msd = meanSquaredDistance(x, lastInputForTwo1);
         if (msd >= ARCConfig.BABY_TWO1_DIVERGENCE_MSD) {
             isTwo1 = true;
             double[] mid = new double[x.length];
             for (int j = 0; j < x.length; j++) mid[j] = 0.5 * (x[j] + lastInputForTwo1[j]);
             xUsed = mid;
         }
     }

     lastWinner = w;


        // Update using STDP
        updateSTDP(xUsed, w, activation);
        if (isTwo1) {
            trace[w] += ARCConfig.BABY_TWO1_TRACE_BOOST;
        }

        // Store current activation for next timestep
        System.arraycopy(activation, 0, lastActivation, 0, activation.length);if (lastInputForTwo1 == null || lastInputForTwo1.length != x.length) {
            lastInputForTwo1 = new double[x.length];
        }
        System.arraycopy(x, 0, lastInputForTwo1, 0, x.length);

    }

    /**
     * A simple "brain print": winner's weight vector (copy).
     */
    public double[] getWinningPattern() {
        return Arrays.copyOf(weights[lastWinner], weights[lastWinner].length);
    }

    private int bmu(double[] x) {
        int best = 0;
        double minD = dist2(x, 0);
        for (int i = 1; i < weights.length; i++) {
            double d = dist2(x, i);
            if (d < minD) {
                best = i;
                minD = d;
            }
        }
        return best;
    }

    public double[] getFullMapFlat() {
        int nodes = weights.length;
        int dim = weights[0].length;
        double[] flat = new double[nodes * dim];

        int k = 0;
        for (int i = 0; i < nodes; i++) {
            for (int j = 0; j < dim; j++) {
                flat[k++] = weights[i][j];
            }
        }
        return flat;
    }

    /**
     * STDP update rule:
     * - If activation increased (spike happened after strong input) → LTP
     * - If activation decreased (spike happened before input faded) → LTD
     * - Neighborhood influence spreads the effect
     */
    private void updateSTDP(double[] x, int w, double[] currentActivation) {
        double lr = ARCConfig.BABY_LEARNING_RATE;
        double radius = ARCConfig.BABY_RADIUS;
        double sigma2 = ARCConfig.BABY_SIGMA * ARCConfig.BABY_SIGMA;

        for (int i = 0; i < weights.length; i++) {
            // Calculate neighborhood influence
            double d = i - w;
            double influence = Math.exp(-(d * d) / sigma2) * radius;

            // STDP: compare current activation to previous
            double deltaActivation = currentActivation[i] - lastActivation[i];

            // If activation increased → strengthen (LTP)
            // If activation decreased → weaken (LTD)
            double stdpFactor;
            if (deltaActivation > 0) {
                // LTP: strengthen connections that predicted this activation
                stdpFactor = aPlus * Math.exp(-1.0 / tauPlus);
            } else {
                // LTD: weaken connections that didn't predict well
                stdpFactor = -aMinus * Math.exp(-1.0 / tauMinus);
            }

            // Update weights with STDP modulation
            for (int j = 0; j < x.length; j++) {
                double diff = x[j] - weights[i][j];

                // Standard SOM update modulated by STDP
                double stdpModulation = 1.0 + stdpFactor * influence;
                weights[i][j] += lr * influence * stdpModulation * diff;

                // Keep weights bounded [0, 1]
                weights[i][j] = Math.max(0.0, Math.min(1.0, weights[i][j]));
            }

            // Update eligibility trace
            trace[i] = trace[i] * 0.95 + influence;
        }
    }
    private static double meanSquaredDistance(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return s / Math.max(1, a.length);
    }

    private double dist2(double[] x, int i) {
        double s = 0.0;
        for (int j = 0; j < x.length; j++) {
            double diff = weights[i][j] - x[j];
            s += diff * diff;
        }
        return s;
    }
}

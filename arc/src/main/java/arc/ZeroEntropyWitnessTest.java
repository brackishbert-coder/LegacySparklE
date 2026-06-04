package arc;

public final class ZeroEntropyWitnessTest {

    public static final class WindowStats {
        public final long windowId;
        public final double entropy;
        public final int uniqueBmus;
        public final double dominantFrac;
        public final double inputVar;
        public final double inputRms;
        public final double meanBmuDist;
        public final double weightDeltaL2;
        public final double bmuChurn;

        public WindowStats(long windowId, double entropy, int uniqueBmus, double dominantFrac,
                           double inputVar, double inputRms, double meanBmuDist,
                           double weightDeltaL2, double bmuChurn) {
            this.windowId = windowId;
            this.entropy = entropy;
            this.uniqueBmus = uniqueBmus;
            this.dominantFrac = dominantFrac;
            this.inputVar = inputVar;
            this.inputRms = inputRms;
            this.meanBmuDist = meanBmuDist;
            this.weightDeltaL2 = weightDeltaL2;
            this.bmuChurn = bmuChurn;
        }
    }

    private static final double ENTROPY_EPS = 1e-12;
    private static final double VAR_EPS = 1e-9;  // tune to your vector scaling
    private static final int K_CONSEC_ZERO = 3;
    private static final int P_PERTURB_WINDOWS = 3;
    private static final int R_RECOVERY_WINDOWS = 20;

    private int consecZero = 0;
    private int perturbLeft = 0;
    private int recoveryLeft = 0;
    private boolean witnessActive = false;

    /** Hook this in right before you feed vectors into the SOM. */
    public double[] maybePerturb(double[] v, java.util.Random rng) {
        if (perturbLeft <= 0) return v;

        double[] out = v.clone();
        // White-noise perturbation (small)
        final double amp = 0.05; // 5% of typical scale; tune
        for (int i = 0; i < out.length; i++) {
            out[i] += amp * rng.nextGaussian();
        }
        return out;
    }

    /** Call once per window with computed stats. */
    public void observe(WindowStats s) {
        boolean isZero = s.entropy <= ENTROPY_EPS;

        // Track zero streak
        if (isZero) consecZero++;
        else consecZero = 0;

        if (!witnessActive && consecZero >= K_CONSEC_ZERO) {
            // Start witness sequence
            witnessActive = true;
            perturbLeft = P_PERTURB_WINDOWS;
            recoveryLeft = R_RECOVERY_WINDOWS;
            log("WITNESS_START", s, "");
        }

        if (!witnessActive) return;

        // During perturbation
        if (perturbLeft > 0) {
            // We expect entropy to rise OR BMU diversity to increase
            if (s.inputVar >= VAR_EPS && s.uniqueBmus == 1 && s.entropy <= ENTROPY_EPS) {
                log("DEGENERATE_SUSPECTED", s,
                        "high inputVar but still zero entropy under perturbation");
            } else {
                log("PERTURB_OK", s, "");
            }
            perturbLeft--;
            return;
        }

        // Recovery phase
        if (recoveryLeft > 0) {
            if (s.entropy <= ENTROPY_EPS && s.uniqueBmus == 1 && s.inputVar >= VAR_EPS) {
                log("ZERO_ENTROPY_WITNESSED", s, "recovered to zero after perturbation");
                // optional: end witness early on success
                witnessActive = false;
            } else {
                log("RECOVERY_OBSERVE", s, "");
                recoveryLeft--;
                if (recoveryLeft == 0) {
                    log("WITNESS_END_NO_RECOVERY", s, "did not recover to zero");
                    witnessActive = false;
                }
            }
        }
    }

    public boolean shouldPerturbNow() {
        return witnessActive && perturbLeft > 0;
    }

    private void log(String tag, WindowStats s, String note) {
        System.out.printf(
            "%s win=%d ent=%.6g uniq=%d dom=%.3f var=%.6g rms=%.6g dist=%.6g dW=%.6g churn=%.6g %s%n",
            tag, s.windowId, s.entropy, s.uniqueBmus, s.dominantFrac,
            s.inputVar, s.inputRms, s.meanBmuDist, s.weightDeltaL2, s.bmuChurn, note
        );
    }
}

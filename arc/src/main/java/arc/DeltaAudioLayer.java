package arc;

import java.util.Random;

/**
 * Minimal delta-sonification layer.
 * Call trigger(intensity01) on events; call nextSample() per audio sample.
 */
public final class DeltaAudioLayer {
    private final float sampleRate;

    private double env = 0.0;
    private double envTarget = 0.0;

    private final double attackCoef;
    private final double decayCoef;

    private int cooldownSamples = 0;
    private final int minCooldownSamples;

    private double clickPhase = 0.0;
    private final double clickHz = 800.0;


    public DeltaAudioLayer(float sampleRate) {
        this.sampleRate = sampleRate;
        this.attackCoef = coefFromTimeMs(sampleRate, 1.0);   // ~1ms
        this.decayCoef  = coefFromTimeMs(sampleRate, 40.0);  // ~40ms
        this.minCooldownSamples = (int) (sampleRate * 0.025); // ~25ms
    }

    /** intensity01 in [0..1]. */
    public void trigger(double intensity01) {
        if (cooldownSamples > 0) return;

        double a = clamp01(intensity01);
        envTarget = Math.max(envTarget, a);
        clickPhase = 0.0;
        cooldownSamples = minCooldownSamples;
    }

    /** Small signal in ~[-1..+1], usually near 0. */
    public double nextSample() {
        if (cooldownSamples > 0) cooldownSamples--;

        if (env < envTarget) env += (envTarget - env) * attackCoef;
        else                 env += (0.0 - env) * decayCoef;

        envTarget = 0.0;
        if (env < 1e-6) return 0.0;

        // Single sound: sine blip (no noise)
        clickPhase += (2.0 * Math.PI * clickHz) / sampleRate;
        if (clickPhase > 2.0 * Math.PI) clickPhase -= 2.0 * Math.PI;

        double out = Math.sin(clickPhase) * env;

        // gentle soft clip safety (optional but fine)
        return Math.tanh(out * 1.2);

    }

    private static double coefFromTimeMs(float sr, double ms) {
        double samples = Math.max(1.0, (ms / 1000.0) * sr);
        return 1.0 - Math.exp(-1.0 / samples);
    }

    private static double clamp01(double x) {
        if (x < 0.0) return 0.0;
        if (x > 1.0) return 1.0;
        return x;
    }
}

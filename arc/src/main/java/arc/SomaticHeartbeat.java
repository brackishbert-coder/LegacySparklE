package arc;

/**
 * Somatic heartbeat oscillator.
 *
 * This is NOT audio. It is a low-frequency physiological clock
 * used to modulate envelopes, timing, and learning energy.
 */
public final class SomaticHeartbeat {

    // Heart rate in beats per minute (typical human range: 40–180)
    private volatile double bpm;

    // Internal phase [0, 2π)
    private double phase = 0.0;

    // Cached increment per sample
    private double phaseIncrement;

    private final double sampleRate;

    public SomaticHeartbeat(double bpm, double sampleRate) {
        this.sampleRate = sampleRate;
        setBpm(bpm);
    }

    public synchronized void setBpm(double bpm) {
        this.bpm = Math.max(10.0, Math.min(240.0, bpm));
        double hz = this.bpm / 60.0;
        this.phaseIncrement = (2.0 * Math.PI * hz) / sampleRate;
    }

    /**
     * Advance heartbeat by one sample.
     * Call this once per generated audio sample.
     */
    public synchronized void tick() {
        phase += phaseIncrement;
        if (phase >= 2.0 * Math.PI) {
            phase -= 2.0 * Math.PI;
        }
    }

    /**
     * Raw phase in radians [0, 2π).
     */
    public synchronized double phase() {
        return phase;
    }

    /**
     * Normalized heartbeat waveform [0,1].
     * This is intentionally asymmetric (more "biological" than sine).
     */
    public synchronized double energy() {
        // Sharpened sine -> heartbeat-like pulse
        double s = Math.sin(phase);
        double pulse = Math.max(0.0, s);
        return pulse * pulse;
    }

    /**
     * True once per heartbeat cycle (near rising edge).
     * Useful for cadence / gating logic.
     */
    public synchronized boolean isBeat() {
        return phase < phaseIncrement * 2.0;
    }
}

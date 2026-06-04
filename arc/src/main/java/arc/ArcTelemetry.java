package arc;

public final class ArcTelemetry {
    public final long nowMs;
    public final long windowMs;

    // Core lines
    public final double entropy01;
    public final double bmuStability01;
    public final double churn01;
    public final double curvDelta01;      // normalized |dMeanAbs|
    public final double two1EffRate01;
    public final double tmr01;
    public final int feedbackTick;        // 0/1

    // ROI lines
    public final double amp01;
    public final double deqRate01;
    public final double curvCoverage01;
    public final double curvEntropy01;
    public final double two1RawRate01;

    // Extra raw-ish fields (useful for probes & tests)
    public final double meanAbsCurv01;
    public final double meanAbsCurvRaw;
    public final double dMeanAbsCurvRaw;

    public final double two1EffRateRaw;
    public final double two1RawRateRaw;
    public final double tmrRaw;

    public final int queueSize;
    public final int queueCap;

    // Optional: expose current BMU for probes
    public final int currentBMU; // -1 if unknown
	private double curv01;

    public ArcTelemetry(
            long nowMs, long windowMs,
            double entropy01, double bmuStability01, double churn01, double curvDelta01,
            double two1EffRate01, double tmr01, double curv01, int feedbackTick,
            double amp01, double deqRate01, double curvCoverage01, double curvEntropy01, double two1RawRate01,
            double meanAbsCurv01, double meanAbsCurvRaw, double dMeanAbsCurvRaw,
            double two1EffRateRaw, double two1RawRateRaw, double tmrRaw,
            int queueSize, int queueCap,
            int currentBMU
    ) {
        this.nowMs = nowMs;
        this.windowMs = windowMs;

        this.entropy01 = entropy01;
        this.bmuStability01 = bmuStability01;
        this.churn01 = churn01;
        this.curvDelta01 = curvDelta01;
        this.two1EffRate01 = two1EffRate01;
        this.tmr01 = tmr01;
		this.curv01 = curv01;
        this.feedbackTick = feedbackTick;

        this.amp01 = amp01;
        this.deqRate01 = deqRate01;
        this.curvCoverage01 = curvCoverage01;
        this.curvEntropy01 = curvEntropy01;
        this.two1RawRate01 = two1RawRate01;

        this.meanAbsCurv01 = meanAbsCurv01;
        this.meanAbsCurvRaw = meanAbsCurvRaw;
        this.dMeanAbsCurvRaw = dMeanAbsCurvRaw;

        this.two1EffRateRaw = two1EffRateRaw;
        this.two1RawRateRaw = two1RawRateRaw;
        this.tmrRaw = tmrRaw;

        this.queueSize = queueSize;
        this.queueCap = queueCap;

        this.currentBMU = currentBMU;
    }
}


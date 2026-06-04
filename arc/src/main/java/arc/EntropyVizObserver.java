package arc;

public final class EntropyVizObserver implements TelemetryObserver {
    private final EntropyVisualizer viz;

    public EntropyVizObserver(EntropyVisualizer viz) {
        this.viz = viz;
    }

    @Override
    public void onTelemetry(ArcTelemetry t) {
        if (viz == null) return;

        viz.addSample(new EntropyVisualizer.Sample(
                t.nowMs,
                t.entropy01,
                t.bmuStability01,
                t.queueSize,
                t.queueCap,

                t.churn01,
                t.curvDelta01,
                t.two1EffRate01,
                t.tmr01,
                t.feedbackTick,

                t.amp01,
                t.deqRate01,
                t.curvCoverage01,
                t.curvEntropy01,
                t.two1RawRate01,

                t.meanAbsCurv01,
                t.meanAbsCurvRaw,
                t.dMeanAbsCurvRaw,

                t.two1EffRateRaw,
                t.two1RawRateRaw,
                t.tmrRaw
        ));
    }
}


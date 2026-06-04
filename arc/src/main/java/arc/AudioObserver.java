package arc;

public final class AudioObserver implements TelemetryObserver {
    private final MusicalAdultSOMAudioOut_DeviceSelect audio;

    public AudioObserver(MusicalAdultSOMAudioOut_DeviceSelect audio) {
        this.audio = audio;
    }

    @Override
    public void onTelemetry(ArcTelemetry t) {
        if (audio == null) return;
        audio.updateEntropy01(t.entropy01);
        audio.updateChurn01(t.churn01);
        audio.updateTmr01(t.tmr01);
        audio.updateCurvatureDelta01(t.curvDelta01);
        audio.updateTwo1EffRate01(t.two1EffRate01);

        if (t.feedbackTick == 1) {
            audio.triggerFeedback(1.0);
        }
    }
}

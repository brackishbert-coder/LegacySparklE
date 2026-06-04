package arc;

import javax.swing.SwingUtilities;

public final class CurvatureViewerObserver implements TelemetryObserver {
    private final AdultSOM adult;
    private final CurvatureSurfaceViewer two1View;
    private final CurvatureSurfaceViewer geomView;

    public CurvatureViewerObserver(AdultSOM adult, CurvatureSurfaceViewer two1View, CurvatureSurfaceViewer geomView) {
        this.adult = adult;
        this.two1View = two1View;
        this.geomView = geomView;
    }

    @Override
    public void onTelemetry(ArcTelemetry t) {
        if (adult == null || two1View == null || geomView == null) return;

        final double[] cTwo1 = adult.getCurvTwo1FlatCopy();
        final double[] cGeom = adult.getCurvGeomFlatCopy();
        final int w = adult.getW();
        final int h = adult.getH();

        SwingUtilities.invokeLater(() -> {
            two1View.setSurfaceFromFlat(cTwo1, h, w);
            geomView.setSurfaceFromFlat(cGeom, h, w);
        });
    }
}

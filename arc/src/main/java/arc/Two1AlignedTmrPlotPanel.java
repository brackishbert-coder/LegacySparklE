package arc;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

public final class Two1AlignedTmrPlotPanel extends JPanel {

    private volatile double[] mean = new double[0];
    private volatile double[] std  = new double[0];
    private volatile int count = 0;
    private volatile int zeroIndex = 0; // where τ=0 is on the x-axis

    public Two1AlignedTmrPlotPanel() {
        setPreferredSize(new Dimension(900, 260));
        setBackground(Color.WHITE);
    }

    public void update(Two1AlignedTmrAverager.AlignedAverageResult r, int zeroIndex) {
        if (r == null) return;
        this.mean = (r.mean == null) ? new double[0] : Arrays.copyOf(r.mean, r.mean.length);
        this.std  = (r.std  == null) ? new double[0] : Arrays.copyOf(r.std,  r.std.length);
        this.count = r.count;
        this.zeroIndex = Math.max(0, Math.min(zeroIndex, this.mean.length == 0 ? 0 : this.mean.length - 1));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int W = getWidth();
            int H = getHeight();

            // Layout
            int padL = 50, padR = 15, padT = 18, padB = 28;
            int x0 = padL, x1 = W - padR;
            int y0 = padT, y1 = H - padB;

            // Title
            g.setColor(Color.DARK_GRAY);
            g.drawString("TWO1-aligned TMR average (mean ± 1σ)   events=" + count, padL, 14);

            // Axes box
            g.setColor(new Color(220, 220, 220));
            g.drawRect(x0, y0, x1 - x0, y1 - y0);

            if (mean.length < 2) {
                g.setColor(Color.GRAY);
                g.drawString("Waiting for enough events...", x0 + 10, y0 + 20);
                return;
            }

            // y scale: since TMR is [0..1], clamp to [0..1] for display
            double yMin = 0.0;
            double yMax = 1.0;

            // x scale: 0..L-1
            int L = mean.length;

            // helper mappers
            java.util.function.IntFunction<Integer> X = (i) ->
                    x0 + (int) Math.round((i / (double) (L - 1)) * (x1 - x0));
            java.util.function.DoubleFunction<Integer> Y = (v) -> {
                double t = (v - yMin) / Math.max(1e-12, (yMax - yMin));
                t = Math.max(0.0, Math.min(1.0, t));
                return y1 - (int) Math.round(t * (y1 - y0));
            };

            // Zero line (τ=0)
            int xZero = X.apply(zeroIndex);
            g.setColor(new Color(255, 180, 180));
            g.drawLine(xZero, y0, xZero, y1);
            g.setColor(Color.GRAY);
            g.drawString("τ=0", xZero + 4, y0 + 12);

            // Shaded band: mean ± std
            if (std.length == mean.length) {
                g.setColor(new Color(180, 200, 255, 90));
                Polygon band = new Polygon();
                for (int i = 0; i < L; i++) {
                    band.addPoint(X.apply(i), Y.apply(mean[i] + std[i]));
                }
                for (int i = L - 1; i >= 0; i--) {
                    band.addPoint(X.apply(i), Y.apply(mean[i] - std[i]));
                }
                g.fillPolygon(band);
            }

            // Mean curve
            g.setColor(new Color(40, 90, 200));
            for (int i = 0; i < L - 1; i++) {
                g.drawLine(X.apply(i), Y.apply(mean[i]), X.apply(i + 1), Y.apply(mean[i + 1]));
            }

            // Minimal y ticks
            g.setColor(Color.GRAY);
            g.drawString("1.0", 10, Y.apply(1.0) + 4);
            g.drawString("0.5", 10, Y.apply(0.5) + 4);
            g.drawString("0.0", 10, Y.apply(0.0) + 4);

        } finally {
            g.dispose();
        }
    }
}

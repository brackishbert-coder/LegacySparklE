package arc;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free real-time instrumentation plotter.
 *
 * Your color semantics (kept EXACT):
 *   BLACK  = Entropy (0..1)
 *   BLUE   = BMU churn01 (0..1)
 *   GREEN  = abs(dMeanCurv) / curvatureDelta01 (0..1)
 *   PURPLE = TWO1 effective rate01 (0..1)
 *   ORANGE = TMR01 (0..1)
 *   RED    = Feedback ticks (vertical)
 *
 * Added "ROI" series (5 extra toggles/lines):
 *   ROI1 = Amp01 (input amplitude EMA, 0..1)
 *   ROI2 = DeqRate01 (dequeue rate normalized, 0..1)
 *   ROI3 = CurvCoverage01 (fraction of nodes |curv|>eps, 0..1)
 *   ROI4 = CurvEntropy01 (spread of curvature mass, 0..1)
 *   ROI5 = Two1RawRate01 (raw TWO1 rate normalized, 0..1)
 *
 * Notes:
 * - All plotted series are expected to already be normalized into [0,1] except where explicitly "raw".
 * - Status text shows both normalized and raw-friendly values where available.
 */
public final class EntropyVisualizer implements AutoCloseable {

    // ---------- Sample ----------
    public static final class Sample {
        public final long tMs;
     // normalized (0..1)
        public final double meanAbsCurv01;   // NEW

        // raw
        public final double meanAbsCurv;     // NEW

        // Core status
        public final double entropy;     // 0..1 (displayed / EMA)
        public final double stability;   // 0..1-ish (whatever AdultSOM uses)
        public final int queueSize;
        public final int queueCap;

        // Core plotted series (normalized 0..1)
        public final double bmuChurn01;        // BLUE
        public final double curvatureDelta01;  // GREEN (abs(dMeanCurv) normalized)
        public double two1EffRate01 = 0;
        public final double tmr01;             // ORANGE
        public final int feedbackTick;         // 0/1 (optional; runner can also call markFeedbackEvent)

        // ROI plotted series (normalized 0..1)
        public final double amp01;             // ROI1
        public final double deqRate01;         // ROI2
        public final double curvCoverage01;    // ROI3
        public final double curvEntropy01;     // ROI4
        public final double two1RawRate01;     // ROI5

        public double meanCurvatureAbs = 0;
        public double meanCurvatureAbsDelta = 0;
        public double two1EffRatePerSec = 0;
        public double two1RawRatePerSec = 0;
        public final double tmr;                  // raw mismatch ratio (0..1)
        public final double meanAbsCurvRaw;      // NEW (raw meanAbs)
        public final double dMeanAbsCurvRaw;     // NEW (raw delta meanAbs)

        // Minimal constructor (kept for compatibility; fills extras with 0)
        public Sample(long tMs, double entropy, double stability, int queueSize, int queueCap) {
            this(
                    tMs, entropy, stability, queueSize, queueCap,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0
            );
        }

        /**
         * FULL constructor (what ARCSystemRunner should call).
         */
        public Sample(
                long tMs,
                double entropy,
                double stability,
                int queueSize,
                int queueCap,
                double bmuChurn01,
                double curvatureDelta01,
                double two1Rate01,
                double tmr01,
                int feedbackTick,
                double roi1Amp01,
                double roi2DeqRate01,
                double roi3CurvCoverage01,
                double roi4CurvEntropy01,
                double roi5Two1RawRate01,
                double meanAbsCurv01,
                double meanAbsCurvRaw,
                double dMeanAbsCurvRaw,
                double two1EffRatePerSec,
                double two1RawRatePerSec,
                double tmrRaw
        ) {
            this.tMs = tMs;
			this.meanAbsCurv = 0;

            this.entropy = entropy;
            this.stability = stability;
            this.queueSize = queueSize;
            this.queueCap = queueCap;

            this.bmuChurn01 = bmuChurn01;
            this.curvatureDelta01 = curvatureDelta01;
            this.two1EffRate01 = two1Rate01;
            this.tmr01 = tmr01;
            this.feedbackTick = feedbackTick;

            this.amp01 = roi1Amp01;
            this.deqRate01 = roi2DeqRate01;
            this.curvCoverage01 = roi3CurvCoverage01;
            this.curvEntropy01 = roi4CurvEntropy01;
            this.two1RawRate01 = roi5Two1RawRate01;

            this.meanAbsCurv01 = meanAbsCurv01;
            this.meanAbsCurvRaw = meanAbsCurvRaw;
            this.dMeanAbsCurvRaw = dMeanAbsCurvRaw;

            this.two1EffRatePerSec = two1EffRatePerSec;
            this.two1RawRatePerSec = two1RawRatePerSec;
            this.tmr = tmrRaw;
        }
    }

    // ---------- Colors ----------
    private static final Color ENTROPY_COLOR   = Color.BLACK;
    private static final Color CHURN_COLOR     = new Color(0, 100, 220);      // BLUE
    private static final Color CURV_COLOR      = new Color(0, 150, 0);        // GREEN
    private static final Color TWO1_EFF_COLOR  = new Color(180, 0, 180);      // PURPLE
    private static final Color TMR_COLOR       = new Color(255, 140, 0);      // ORANGE
    private static final Color FEEDBACK_COLOR  = new Color(220, 0, 0, 180);   // RED ticks

    // ROI palette (distinct but readable)
    private static final Color ROI1_AMP_COLOR        = new Color(120, 120, 120); // gray
    private static final Color ROI2_DEQ_COLOR        = new Color(0, 170, 170);   // teal
    private static final Color ROI3_COVER_COLOR      = new Color(140, 90, 0);    // brown
    private static final Color ROI4_CURV_ENT_COLOR   = new Color(120, 0, 200);   // violet-ish (different from purple)
    private static final Color ROI5_TWO1_RAW_COLOR   = new Color(200, 0, 80);    // magenta-red
    private static final Color MEAN_CURV_COLOR = new Color(0, 170, 140);

    // ---------- UI ----------
    private final JFrame frame;
    private final PlotPanel plot;
    private volatile boolean showMeanCurv = true;

    // bounded buffers
    private final List<Sample> samples = new ArrayList<>();
    private final List<Long> feedbackEventsMs = new ArrayList<>();
    private final int maxSamples;

    // toggles (defaults ON for core; ROI defaults OFF so you can opt-in)
    private volatile boolean showEntropy = true;
    private volatile boolean showBmuChurn = true;
    private volatile boolean showCurvatureDelta = true;
    private volatile boolean showTwo1EffRate = true;
    private volatile boolean showTmr = true;
    private volatile boolean showFeedbackTicks = true;

    private volatile boolean showRoi1Amp = false;
    private volatile boolean showRoi2Deq = false;
    private volatile boolean showRoi3CurvCover = false;
    private volatile boolean showRoi4CurvEnt = false;
    private volatile boolean showRoi5Two1Raw = false;
    public EntropyVisualizer(String title, int maxSamples) {
        this.maxSamples = Math.max(200, maxSamples);
        this.plot = new PlotPanel();

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));

        JCheckBox cbEntropy = new JCheckBox("Entropy", showEntropy);
        JCheckBox cbChurn   = new JCheckBox("BMU churn (0..1)", showBmuChurn);
        JCheckBox cbCurv    = new JCheckBox("abs(Δ meanCurv) (0..1)", showCurvatureDelta);
        JCheckBox cbTwo1    = new JCheckBox("TWO1 eff rate (0..1)", showTwo1EffRate);
        JCheckBox cbTmr     = new JCheckBox("TMR (0..1)", showTmr);
        JCheckBox cbTicks   = new JCheckBox("Feedback ticks", showFeedbackTicks);

        JCheckBox cbR1 = new JCheckBox("ROI1 amp (0..1)", showRoi1Amp);
        JCheckBox cbR2 = new JCheckBox("ROI2 deqRate (0..1)", showRoi2Deq);
        JCheckBox cbR3 = new JCheckBox("ROI3 curvCover (0..1)", showRoi3CurvCover);
        JCheckBox cbR4 = new JCheckBox("ROI4 curvEntropy (0..1)", showRoi4CurvEnt);
        JCheckBox cbR5 = new JCheckBox("ROI5 two1Raw (0..1)", showRoi5Two1Raw);

        cbEntropy.addActionListener(e -> { showEntropy = cbEntropy.isSelected(); plot.repaint(); });
        cbChurn.addActionListener(e   -> { showBmuChurn = cbChurn.isSelected(); plot.repaint(); });
        cbCurv.addActionListener(e    -> { showCurvatureDelta = cbCurv.isSelected(); plot.repaint(); });
        cbTwo1.addActionListener(e    -> { showTwo1EffRate = cbTwo1.isSelected(); plot.repaint(); });
        cbTmr.addActionListener(e     -> { showTmr = cbTmr.isSelected(); plot.repaint(); });
        cbTicks.addActionListener(e   -> { showFeedbackTicks = cbTicks.isSelected(); plot.repaint(); });

        cbR1.addActionListener(e -> { showRoi1Amp = cbR1.isSelected(); plot.repaint(); });
        cbR2.addActionListener(e -> { showRoi2Deq = cbR2.isSelected(); plot.repaint(); });
        cbR3.addActionListener(e -> { showRoi3CurvCover = cbR3.isSelected(); plot.repaint(); });
        cbR4.addActionListener(e -> { showRoi4CurvEnt = cbR4.isSelected(); plot.repaint(); });
        cbR5.addActionListener(e -> { showRoi5Two1Raw = cbR5.isSelected(); plot.repaint(); });
        JCheckBox cbMeanCurv = new JCheckBox("meanAbsCurv (0..1)", showMeanCurv);
        cbMeanCurv.addActionListener(e -> {
            showMeanCurv = cbMeanCurv.isSelected();
            plot.repaint();
        });
        controls.add(cbMeanCurv);
       

        controls.add(cbEntropy);
        controls.add(cbChurn);
        controls.add(cbCurv);
        controls.add(cbTwo1);
        controls.add(cbTmr);
        controls.add(cbTicks);

        controls.add(new JSeparator(SwingConstants.VERTICAL));
        controls.add(cbR1);
        controls.add(cbR2);
        controls.add(cbR3);
        controls.add(cbR4);
        controls.add(cbR5);

        this.frame = new JFrame(title);
        this.frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        this.frame.setLayout(new BorderLayout());
        this.frame.add(plot, BorderLayout.CENTER);
        this.frame.add(controls, BorderLayout.SOUTH);
        this.frame.setSize(1100, 620);
        this.frame.setLocationByPlatform(true);
        this.frame.setVisible(true);

        new Timer(100, e -> plot.repaint()).start();
    }

    public void addSample(Sample s) {
        synchronized (samples) {
            samples.add(s);
            int overflow = samples.size() - maxSamples;
            if (overflow > 0) samples.subList(0, overflow).clear();
        }

        // Optional: if runner uses per-sample tick instead of markFeedbackEvent()
        if (s.feedbackTick != 0) {
            markFeedbackEvent(s.tMs);
        }
    }

    public void markFeedbackEvent(long tMs) {
        synchronized (feedbackEventsMs) {
            feedbackEventsMs.add(tMs);
            int overflow = feedbackEventsMs.size() - maxSamples;
            if (overflow > 0) feedbackEventsMs.subList(0, overflow).clear();
        }
    }

    @Override
    public void close() {
        SwingUtilities.invokeLater(frame::dispose);
    }

    private interface ValueFn {
        double get(Sample s);
    }

    // ---------- Plot panel ----------
    private final class PlotPanel extends JPanel {
        PlotPanel() { setBackground(Color.WHITE); }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();

            try {
                int w = getWidth();
                int h = getHeight();

                // margins
                int left = 64;
                int right = 18;
                int top = 18;
                int bottom = 56;

                int pw = Math.max(1, w - left - right);
                int ph = Math.max(1, h - top - bottom);

                // copy buffers
                List<Sample> sCopy;
                List<Long> evCopy;
                synchronized (samples) { sCopy = new ArrayList<>(samples); }
                synchronized (feedbackEventsMs) { evCopy = new ArrayList<>(feedbackEventsMs); }

                // axes frame
                g.setColor(Color.BLACK);
                g.drawRect(left, top, pw, ph);

                if (sCopy.size() < 2) {
                    g.setColor(Color.DARK_GRAY);
                    if (sCopy.size() == 1) {
                        g.drawString(statusText(sCopy.get(0), 0.0), left + 8, top + 16);
                    } else {
                        g.drawString("waiting for samples...", left + 8, top + 16);
                    }
                    return;
                }

                // dynamic y-range based on visible series (still clamped within [0,1])
                double yMin = 0.0;
                double yMax = 1.0;
                double min = Double.POSITIVE_INFINITY;
                double max = Double.NEGATIVE_INFINITY;

                for (Sample s : sCopy) {
                    if (showEntropy)          { min = Math.min(min, clamp01(s.entropy));             max = Math.max(max, clamp01(s.entropy)); }
                    if (showBmuChurn)         { min = Math.min(min, clamp01(s.bmuChurn01));          max = Math.max(max, clamp01(s.bmuChurn01)); }
                    if (showCurvatureDelta)   { min = Math.min(min, clamp01(s.curvatureDelta01));   max = Math.max(max, clamp01(s.curvatureDelta01)); }
                    if (showTwo1EffRate)      { min = Math.min(min, clamp01(s.two1EffRate01));      max = Math.max(max, clamp01(s.two1EffRate01)); }
                    if (showTmr)              { min = Math.min(min, clamp01(s.tmr01));              max = Math.max(max, clamp01(s.tmr01)); }

                    if (showRoi1Amp)          { min = Math.min(min, clamp01(s.amp01));              max = Math.max(max, clamp01(s.amp01)); }
                    if (showRoi2Deq)          { min = Math.min(min, clamp01(s.deqRate01));          max = Math.max(max, clamp01(s.deqRate01)); }
                    if (showRoi3CurvCover)    { min = Math.min(min, clamp01(s.curvCoverage01));     max = Math.max(max, clamp01(s.curvCoverage01)); }
                    if (showRoi4CurvEnt)      { min = Math.min(min, clamp01(s.curvEntropy01));      max = Math.max(max, clamp01(s.curvEntropy01)); }
                    if (showRoi5Two1Raw)      { min = Math.min(min, clamp01(s.two1RawRate01));      max = Math.max(max, clamp01(s.two1RawRate01)); }
                }

                if (!Double.isFinite(min) || !Double.isFinite(max) || (max - min) < 1e-6) {
                    yMin = 0.0; yMax = 1.0;
                } else {
                    double pad = 0.05 * (max - min);
                    yMin = Math.max(0.0, min - pad);
                    yMax = Math.min(1.0, max + pad);
                }

                // y labels
                g.setColor(Color.BLACK);
                g.drawString(String.format("%.4f", yMax), 10, top + 12);
                g.drawString(String.format("%.4f", yMin), 10, top + ph);
                g.drawString(String.format("%.4f", 0.5 * (yMin + yMax)), 10, top + ph / 2);

                // time span
                long t0 = sCopy.get(0).tMs;
                long t1 = sCopy.get(sCopy.size() - 1).tMs;
                long dt = Math.max(1L, t1 - t0);

                // grid
                g.setColor(new Color(235, 235, 235));
                for (int i = 1; i < 5; i++) {
                    int yy = top + (ph * i) / 5;
                    g.drawLine(left, yy, left + pw, yy);
                }

                // feedback ticks (draw on top; clamp to visible range)
                if (showFeedbackTicks && !evCopy.isEmpty()) {
                    Stroke old = g.getStroke();
                    g.setStroke(new BasicStroke(2.0f));
                    g.setColor(FEEDBACK_COLOR);

                    for (Long t : evCopy) {
                        long tc = t;
                        if (tc < t0) tc = t0;
                        if (tc > t1) tc = t1;

                        int x = left + (int) ((tc - t0) * 1.0 * pw / dt);
                        g.drawLine(x, top, x, top + ph);
                    }

                    g.setStroke(old);
                }

                // core series
                if (showEntropy) {
                    drawSeries(g, sCopy, t0, dt, left, top, pw, ph, yMin, yMax,
                            ENTROPY_COLOR, (s) -> clamp01(s.entropy));
                }
                if (showBmuChurn) {
                    drawSeries(g, sCopy, t0, dt, left, top, pw, ph, yMin, yMax,
                            CHURN_COLOR, (s) -> clamp01(s.bmuChurn01));
                }
                if (showCurvatureDelta) {
                    drawSeries(g, sCopy, t0, dt, left, top, pw, ph, yMin, yMax,
                            CURV_COLOR, (s) -> clamp01(s.curvatureDelta01));
                }
                if (showTwo1EffRate) {
                    drawSeries(g, sCopy, t0, dt, left, top, pw, ph, yMin, yMax,
                            TWO1_EFF_COLOR, (s) -> clamp01(s.two1EffRate01));
                }
                if (showTmr) {
                    drawSeries(g, sCopy, t0, dt, left, top, pw, ph, yMin, yMax,
                            TMR_COLOR, (s) -> clamp01(s.tmr01));
                }

                // ROI series
                if (showRoi1Amp) {
                    drawSeries(g, sCopy, t0, dt, left, top, pw, ph, yMin, yMax,
                            ROI1_AMP_COLOR, (s) -> clamp01(s.amp01));
                }
                if (showRoi2Deq) {
                    drawSeries(g, sCopy, t0, dt, left, top, pw, ph, yMin, yMax,
                            ROI2_DEQ_COLOR, (s) -> clamp01(s.deqRate01));
                }
                if (showRoi3CurvCover) {
                    drawSeries(g, sCopy, t0, dt, left, top, pw, ph, yMin, yMax,
                            ROI3_COVER_COLOR, (s) -> clamp01(s.curvCoverage01));
                }
                if (showRoi4CurvEnt) {
                    drawSeries(g, sCopy, t0, dt, left, top, pw, ph, yMin, yMax,
                            ROI4_CURV_ENT_COLOR, (s) -> clamp01(s.curvEntropy01));
                }
                if (showRoi5Two1Raw) {
                    drawSeries(g, sCopy, t0, dt, left, top, pw, ph, yMin, yMax,
                            ROI5_TWO1_RAW_COLOR, (s) -> clamp01(s.two1RawRate01));
                }
                if (showMeanCurv) {
                    drawSeries(
                        g, sCopy, t0, dt,
                        left, top, pw, ph,
                        yMin, yMax,
                        MEAN_CURV_COLOR,
                        s -> clamp01(s.meanAbsCurv01)
                    );
                }
                
                // status/legend
                Sample last = sCopy.get(sCopy.size() - 1);
                double windowSec = dt / 1000.0;

                g.setColor(Color.DARK_GRAY);
                g.drawString(statusText(last, windowSec), left + 8, top + 16);

                drawLegend(g, left + 8, top + ph + 26);

            } finally {
                g.dispose();
            }
        }

        private void drawLegend(Graphics2D g, int x0, int y0) {
            int x = x0;
            int y = y0;

            // core legend
            x = legendItem(g, x, y, ENTROPY_COLOR, "Entropy");
            x = legendItem(g, x, y, CHURN_COLOR, "BMU churn");
            x = legendItem(g, x, y, CURV_COLOR, "abs(dCurv)");
            x = legendItem(g, x, y, TWO1_EFF_COLOR, "TWO1 eff");
            x = legendItem(g, x, y, TMR_COLOR, "TMR");

            // ticks legend
            g.setColor(FEEDBACK_COLOR);
            g.drawLine(x, y - 6, x + 18, y - 6);
            g.setColor(Color.BLACK);
            g.drawString("Feedback", x + 24, y - 2);
            x += 110;

            // ROI legend
            x = legendItem(g, x, y, ROI1_AMP_COLOR, "ROI1 amp");
            x = legendItem(g, x, y, ROI2_DEQ_COLOR, "ROI2 deq");
            x = legendItem(g, x, y, ROI3_COVER_COLOR, "ROI3 cover");
            x = legendItem(g, x, y, ROI4_CURV_ENT_COLOR, "ROI4 curvH");
           x= legendItem(g, x, y, ROI5_TWO1_RAW_COLOR, "ROI5 two1Raw");
            g.setColor(MEAN_CURV_COLOR);
            g.drawString("meanAbsCurv", x, y);
            x += 120;
            

        }

        private int legendItem(Graphics2D g, int x, int y, Color c, String label) {
            g.setColor(c);
            g.drawLine(x, y - 6, x + 18, y - 6);
            g.setColor(Color.BLACK);
            g.drawString(label, x + 24, y - 2);
            return x + 100;
        }

        private void drawSeries(
                Graphics2D g,
                List<Sample> sCopy,
                long t0, long dt,
                int left, int top, int pw, int ph,
                double yMin, double yMax,
                Color color,
                ValueFn fn
        ) {
            g.setColor(color);

            final double invDt = 1.0 / Math.max(1.0, (double) dt);
            final double denom = Math.max(1e-12, (yMax - yMin));
            final double pad = 0.02;
            final double scale = 1.0 - 2.0 * pad;

            int prevX = Integer.MIN_VALUE;
            int prevY = Integer.MIN_VALUE;

            for (int i = 0; i < sCopy.size(); i++) {
                Sample s = sCopy.get(i);

                long t = s.tMs - t0;
                if (t < 0) t = 0;
                if (t > dt) t = dt;

                int x = left + (int) Math.round(t * invDt * pw);

                double v = fn.get(s);
                double yn = (v - yMin) / denom;

                if (Double.isNaN(yn) || Double.isInfinite(yn)) yn = 0.0;
                if (yn < 0.0) yn = 0.0;
                if (yn > 1.0) yn = 1.0;

                yn = pad + scale * yn;

                int y = top + (int) Math.round((1.0 - yn) * ph);

                if (prevX >= 0 && x > prevX) {
                    g.drawLine(prevX, prevY, x, y);
                }
                if (x > prevX) {
                    prevX = x;
                    prevY = y;
                } else {
                    prevY = y;
                }
            }
        }

        private String statusText(Sample s, double windowSec) {
            // windowSec is informational (plot window width), not "metrics window"
            return "entropy=" + fmt4(s.entropy)
                    + " | stability=" + fmt3(s.stability)
                    + " | queue=" + s.queueSize + "/" + s.queueCap
                    + " | churn01=" + fmt4(s.bmuChurn01)
                    + " | dCurv01=" + fmt4(s.curvatureDelta01)
                    + " | two1Eff01=" + fmt4(s.two1EffRate01)
                    + " | tmr01=" + fmt4(s.tmr01)
                    + " | meanCurvAbs=" + fmt6(s.meanCurvatureAbs)
                    + " | dMeanCurvAbs=" + fmt6(s.meanCurvatureAbsDelta)
                    + " | two1Eff/s=" + fmt3(s.two1EffRatePerSec)
                    + " | two1Raw/s=" + fmt3(s.two1RawRatePerSec)
                    + " | tmr=" + fmt3(s.tmr)
                    + " | meanAbs01=" + String.format("%.4f", s.meanAbsCurv01)
                    + " | meanAbs=" + String.format("%.6f", s.meanAbsCurvRaw)
                    + " | dMeanAbs=" + String.format("%.6f", s.dMeanAbsCurvRaw)


;
        }

        private String fmt3(double v) { return String.format("%.3f", v); }
        private String fmt4(double v) { return String.format("%.4f", v); }
        private String fmt6(double v) { return String.format("%.6f", v); }

        private double clamp01(double v) {
            if (v < 0.0) return 0.0;
            if (v > 1.0) return 1.0;
            return v;
        }
    }
}

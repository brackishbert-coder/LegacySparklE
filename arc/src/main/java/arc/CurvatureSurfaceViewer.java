package arc;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicReference;

public final class CurvatureSurfaceViewer extends JPanel {

    public enum Palette { SEQUENTIAL, DIVERGING }

    public enum RenderMode {
        RAW,            // render v
        DELTA_ABS,      // render |v - prev|
        DELTA_SIGNED,   // render (v - prev)
        DEMEAN_EMA      // render (v - ema)
    }

    // UI state
    private volatile boolean frozen = false;
    private volatile boolean autoScale = true;
    private volatile boolean logScale = false;
    private volatile boolean smoothing = false;
    private volatile Palette palette = Palette.SEQUENTIAL;
    private volatile RenderMode mode = RenderMode.RAW;

    private volatile double fixedMin = 0.0;
    private volatile double fixedMax = 1.0;

    // log scaler: y = log10(1 + k*|v|)
    private volatile double logK = 1.0;

    // EMA baseline
    private volatile double emaAlpha = 0.05; // 0..1 (higher follows faster)
    private double[][] emaSurface = null;

    // previous frame
    private double[][] prevSurface = null;

    // data handoff
    private final AtomicReference<double[][]> surfaceRef = new AtomicReference<>(null);

    private volatile int surfW = 0;
    private volatile int surfH = 0;

    private volatile BufferedImage img = null;

    private volatile int hoverX = -1;
    private volatile int hoverY = -1;

    private volatile double lastMin = 0.0;
    private volatile double lastMax = 1.0;

    private final DecimalFormat df = new DecimalFormat("0.000000");

    public CurvatureSurfaceViewer() {
        setPreferredSize(new Dimension(520, 520));
        setBackground(Color.WHITE);

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) { updateHover(e.getX(), e.getY()); }
            @Override public void mouseDragged(MouseEvent e) { updateHover(e.getX(), e.getY()); }
        });

        addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) {
                hoverX = -1; hoverY = -1;
                repaint();
            }
        });

        new Timer(60, e -> { if (!frozen) repaint(); }).start();
    }

    // -----------------------------
    // External API: push frames
    // -----------------------------
    public void setSurface(double[][] surface) {
        if (surface == null || surface.length == 0 || surface[0].length == 0) return;
        surfaceRef.set(surface);
        surfH = surface.length;
        surfW = surface[0].length;
        if (!frozen) repaint();
    }

    public void setSurfaceFromFlat(double[] flat, int h, int w) {
        if (flat == null || flat.length < h * w || h <= 0 || w <= 0) return;
        double[][] s = new double[h][w];
        int k = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) s[y][x] = flat[k++];
        setSurface(s);
    }

    // -----------------------------
    // Controls
    // -----------------------------
    public void setFrozen(boolean frozen) { this.frozen = frozen; repaint(); }
    public boolean isFrozen() { return frozen; }

    public void setAutoScale(boolean autoScale) { this.autoScale = autoScale; repaint(); }
    public boolean isAutoScale() { return autoScale; }

    public void setFixedRange(double min, double max) {
        this.fixedMin = min;
        this.fixedMax = max;
        this.autoScale = false;
        repaint();
    }

    public void setLogScale(boolean logScale) { this.logScale = logScale; repaint(); }
    public boolean isLogScale() { return logScale; }

    public void setLogK(double k) { this.logK = Math.max(1e-12, k); repaint(); }

    public void setSmoothing(boolean smoothing) { this.smoothing = smoothing; repaint(); }

    public void setPalette(Palette p) { this.palette = p; repaint(); }

    public void setRenderMode(RenderMode m) {
        this.mode = m;
        // sensible default palette
        if (m == RenderMode.DELTA_SIGNED || m == RenderMode.DEMEAN_EMA) {
            this.palette = Palette.DIVERGING;
        }
        repaint();
    }

    public void setEmaAlpha(double a) {
        this.emaAlpha = clamp(a, 0.0, 1.0);
        repaint();
    }

    public void resetBaselines() {
        prevSurface = null;
        emaSurface = null;
        repaint();
    }

    public double getLastMin() { return lastMin; }
    public double getLastMax() { return lastMax; }

    // -----------------------------
    // Painting
    // -----------------------------
    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        try {
            int W = getWidth();
            int H = getHeight();

            int top = 10;
            int bottom = 52;
            int left = 10;
            int right = 10;

            int pw = Math.max(1, W - left - right);
            int ph = Math.max(1, H - top - bottom);

            double[][] surface = surfaceRef.get();
            if (surface == null) {
                g.setColor(Color.DARK_GRAY);
                g.drawString("waiting for curvature surface...", left + 8, top + 16);
                return;
            }

            img = renderSurface(surface, pw, ph);

            g.drawImage(img, left, top, null);
            g.setColor(Color.BLACK);
            g.drawRect(left, top, pw, ph);

            drawHover(g, surface, left, top, pw, ph);
            drawStatus(g, surface, left, top + ph + 24);

        } finally {
            g.dispose();
        }
    }

    private BufferedImage renderSurface(double[][] srcRaw, int pw, int ph) {
        int h = srcRaw.length;
        int w = srcRaw[0].length;

        // optional smoothing
        double[][] src = smoothing ? boxBlur3(srcRaw) : srcRaw;

        // ensure baselines allocated
        if (prevSurface == null || prevSurface.length != h || prevSurface[0].length != w) {
            prevSurface = deepCopy2(src);
        }
        if (emaSurface == null || emaSurface.length != h || emaSurface[0].length != w) {
            emaSurface = deepCopy2(src);
        }

        // compute derived surface for render mode
        double[][] render = new double[h][w];

        // update ema baseline (always follows RAW src)
        if (emaAlpha > 0.0) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double v = src[y][x];
                    double e = emaSurface[y][x];
                    emaSurface[y][x] = e + emaAlpha * (v - e);
                }
            }
        }

        // compute mode output
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = src[y][x];
                double p = prevSurface[y][x];
                double e = emaSurface[y][x];

                double out;
                switch (mode) {
                    case DELTA_ABS:    out = Math.abs(v - p); break;
                    case DELTA_SIGNED: out = (v - p); break;
                    case DEMEAN_EMA:   out = (v - e); break;
                    case RAW:
                    default:           out = v; break;
                }
                render[y][x] = out;
            }
        }

        // AFTER computing delta, update prev (so delta is frame-to-frame)
        for (int y = 0; y < h; y++) {
            System.arraycopy(src[y], 0, prevSurface[y], 0, w);
        }

        // compute min/max (from rendered surface)
        double min, max;
        if (autoScale) {
            min = Double.POSITIVE_INFINITY;
            max = Double.NEGATIVE_INFINITY;
            for (int y = 0; y < h; y++) {
                double[] row = render[y];
                for (int x = 0; x < w; x++) {
                    double v = row[x];
                    if (!Double.isFinite(v)) continue;
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
            if (!Double.isFinite(min) || !Double.isFinite(max) || Math.abs(max - min) < 1e-30) {
                min = 0.0; max = 1.0;
            }
        } else {
            min = fixedMin;
            max = fixedMax;
            if (Math.abs(max - min) < 1e-30) max = min + 1.0;
        }

        lastMin = min;
        lastMax = max;

        final double denom = Math.max(1e-30, (max - min));

        BufferedImage out = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);

        for (int py = 0; py < ph; py++) {
            int cy = (int) ((py / (double) Math.max(1, ph - 1)) * (h - 1));
            double[] row = render[cy];

            for (int px = 0; px < pw; px++) {
                int cx = (int) ((px / (double) Math.max(1, pw - 1)) * (w - 1));

                double v = row[cx];
                if (!Double.isFinite(v)) v = 0.0;

                double t = normalize(v, min, denom);
                int rgb = colorFor(v, t);
                out.setRGB(px, py, rgb);
            }
        }

        return out;
    }

    private double normalize(double v, double min, double denom) {
        double t = (v - min) / denom;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;

        if (logScale) {
            double y = Math.log10(1.0 + logK * Math.abs(v));
            double yMax = Math.log10(1.0 + logK * Math.max(Math.abs(lastMin), Math.abs(lastMax)));
            double yy = (yMax <= 1e-30) ? 0.0 : (y / yMax);
            if (yy < 0.0) yy = 0.0;
            if (yy > 1.0) yy = 1.0;
            return yy;
        }

        return t;
    }

    private int colorFor(double v, double t) {
        if (palette == Palette.DIVERGING) {
            // Diverging centered at 0 (best if you set fixed symmetric range [-A,+A])
            if (v >= 0) return lerpRGB(0xFFFFFF, 0xCC0000, t);
            else        return lerpRGB(0xFFFFFF, 0x0044CC, t);
        }
        return sequentialRamp(t);
    }

    // Hover
    private void updateHover(int mx, int my) {
        int W = getWidth();
        int H = getHeight();
        int top = 10, bottom = 52, left = 10, right = 10;
        int pw = Math.max(1, W - left - right);
        int ph = Math.max(1, H - top - bottom);

        int px = mx - left;
        int py = my - top;

        if (px < 0 || py < 0 || px >= pw || py >= ph) {
            hoverX = -1; hoverY = -1;
            repaint();
            return;
        }

        int x = (int) ((px / (double) Math.max(1, pw - 1)) * Math.max(0, surfW - 1));
        int y = (int) ((py / (double) Math.max(1, ph - 1)) * Math.max(0, surfH - 1));

        hoverX = x;
        hoverY = y;
        repaint();
    }

    private void drawHover(Graphics2D g, double[][] surfaceRaw, int left, int top, int pw, int ph) {
        if (hoverX < 0 || hoverY < 0) return;
        if (hoverY >= surfaceRaw.length || hoverX >= surfaceRaw[0].length) return;

        int xPix = left + (int) Math.round((hoverX / (double) Math.max(1, surfaceRaw[0].length - 1)) * (pw - 1));
        int yPix = top + (int) Math.round((hoverY / (double) Math.max(1, surfaceRaw.length - 1)) * (ph - 1));

        g.setColor(new Color(0, 0, 0, 80));
        g.drawLine(left, yPix, left + pw, yPix);
        g.drawLine(xPix, top, xPix, top + ph);

        // show RAW at hover (plus helpful baseline info if available)
        double v = surfaceRaw[hoverY][hoverX];
        double pv = (prevSurface != null) ? prevSurface[hoverY][hoverX] : v;
        double ev = (emaSurface != null) ? emaSurface[hoverY][hoverX] : v;

        String msg = "x=" + hoverX + " y=" + hoverY +
                "  raw=" + df.format(v) +
                "  prev=" + df.format(pv) +
                "  ema=" + df.format(ev);

        g.setColor(new Color(255,255,255,220));
        g.fillRoundRect(left + 8, top + 8, Math.min(pw - 16, 520), 22, 10, 10);
        g.setColor(Color.BLACK);
        g.drawRoundRect(left + 8, top + 8, Math.min(pw - 16, 520), 22, 10, 10);
        g.drawString(msg, left + 16, top + 24);
    }

    private void drawStatus(Graphics2D g, double[][] surface, int x, int y) {
        g.setColor(Color.DARK_GRAY);
        String s = "mode=" + mode +
                "  min=" + df.format(lastMin) +
                "  max=" + df.format(lastMax) +
                "  autoScale=" + autoScale +
                "  log=" + logScale +
                "  smooth=" + smoothing +
                "  palette=" + palette +
                "  emaAlpha=" + df.format(emaAlpha) +
                "  frozen=" + frozen +
                "  (" + surface[0].length + "x" + surface.length + ")";
        g.drawString(s, x + 4, y);
    }

    // Palette helpers
    private static int sequentialRamp(double t) {
        if (t <= 0.15) return lerpRGB(0x000000, 0x0033CC, t / 0.15);
        if (t <= 0.30) return lerpRGB(0x0033CC, 0x00CCCC, (t - 0.15) / 0.15);
        if (t <= 0.50) return lerpRGB(0x00CCCC, 0x00AA00, (t - 0.30) / 0.20);
        if (t <= 0.70) return lerpRGB(0x00AA00, 0xFFDD00, (t - 0.50) / 0.20);
        if (t <= 0.85) return lerpRGB(0xFFDD00, 0xCC0000, (t - 0.70) / 0.15);
        return lerpRGB(0xCC0000, 0xFFFFFF, (t - 0.85) / 0.15);
    }

    private static int lerpRGB(int a, int b, double t) {
        t = clamp(t, 0.0, 1.0);
        int ar = (a >>> 16) & 255, ag = (a >>> 8) & 255, ab = (a) & 255;
        int br = (b >>> 16) & 255, bg = (b >>> 8) & 255, bb = (b) & 255;
        int r = (int) Math.round(ar + (br - ar) * t);
        int g = (int) Math.round(ag + (bg - ag) * t);
        int bl = (int) Math.round(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    // Smoothing
    private static double[][] boxBlur3(double[][] src) {
        int h = src.length;
        int w = src[0].length;
        double[][] out = new double[h][w];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - 1);
            int y1 = y;
            int y2 = Math.min(h - 1, y + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - 1);
                int x1 = x;
                int x2 = Math.min(w - 1, x + 1);
                double s = 0.0;
                s += src[y0][x0]; s += src[y0][x1]; s += src[y0][x2];
                s += src[y1][x0]; s += src[y1][x1]; s += src[y1][x2];
                s += src[y2][x0]; s += src[y2][x1]; s += src[y2][x2];
                out[y][x] = s / 9.0;
            }
        }
        return out;
    }

    private static double[][] deepCopy2(double[][] src) {
        int h = src.length;
        int w = src[0].length;
        double[][] out = new double[h][w];
        for (int y = 0; y < h; y++) System.arraycopy(src[y], 0, out[y], 0, w);
        return out;
    }

    private static double clamp(double v, double a, double b) {
        return (v < a) ? a : (v > b) ? b : v;
    }

    // -----------------------------
    // Static launcher w/ controls
    // -----------------------------
    public static JFrame showInFrame(String title, CurvatureSurfaceViewer viewer) {
        JFrame f = new JFrame(title);
        f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));

        JCheckBox cbFreeze = new JCheckBox("Freeze", viewer.isFrozen());
        JCheckBox cbAuto = new JCheckBox("Auto scale", viewer.isAutoScale());
        JCheckBox cbLog = new JCheckBox("Log scale", viewer.isLogScale());
        JCheckBox cbSmooth = new JCheckBox("Smooth", false);

        JComboBox<Palette> pal = new JComboBox<>(Palette.values());
        pal.setSelectedItem(Palette.SEQUENTIAL);

        JComboBox<RenderMode> mode = new JComboBox<>(RenderMode.values());
        mode.setSelectedItem(RenderMode.RAW);

        JTextField tfMin = new JTextField("0.0", 8);
        JTextField tfMax = new JTextField("1.0", 8);
        JButton btnSetRange = new JButton("Set range");
        JButton btnResetAuto = new JButton("Reset auto");

        JTextField tfLogK = new JTextField("1.0", 6);
        JButton btnLogK = new JButton("Set logK");

        JTextField tfEmaA = new JTextField("0.05", 6);
        JButton btnEmaA = new JButton("Set emaAlpha");

        JButton btnResetBase = new JButton("Reset baseline");

        cbFreeze.addActionListener(e -> viewer.setFrozen(cbFreeze.isSelected()));
        cbAuto.addActionListener(e -> viewer.setAutoScale(cbAuto.isSelected()));
        cbLog.addActionListener(e -> viewer.setLogScale(cbLog.isSelected()));
        cbSmooth.addActionListener(e -> viewer.setSmoothing(cbSmooth.isSelected()));

        pal.addActionListener(e -> viewer.setPalette((Palette) pal.getSelectedItem()));
        mode.addActionListener(e -> viewer.setRenderMode((RenderMode) mode.getSelectedItem()));

        btnSetRange.addActionListener(e -> {
            try {
                double mn = Double.parseDouble(tfMin.getText().trim());
                double mx = Double.parseDouble(tfMax.getText().trim());
                viewer.setFixedRange(mn, mx);
                cbAuto.setSelected(false);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(f, "Bad range", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        btnResetAuto.addActionListener(e -> {
            viewer.setAutoScale(true);
            cbAuto.setSelected(true);
        });

        btnLogK.addActionListener(e -> {
            try {
                double k = Double.parseDouble(tfLogK.getText().trim());
                viewer.setLogK(k);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(f, "Bad logK", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        btnEmaA.addActionListener(e -> {
            try {
                double a = Double.parseDouble(tfEmaA.getText().trim());
                viewer.setEmaAlpha(a);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(f, "Bad emaAlpha", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        btnResetBase.addActionListener(e -> viewer.resetBaselines());

        controls.add(cbFreeze);
        controls.add(cbAuto);
        controls.add(cbLog);
        controls.add(new JLabel("logK:"));
        controls.add(tfLogK);
        controls.add(btnLogK);

        controls.add(cbSmooth);

        controls.add(new JLabel("Mode:"));
        controls.add(mode);

        controls.add(new JLabel("Palette:"));
        controls.add(pal);

        controls.add(new JLabel("emaAlpha:"));
        controls.add(tfEmaA);
        controls.add(btnEmaA);
        controls.add(btnResetBase);

        controls.add(new JLabel("min:"));
        controls.add(tfMin);
        controls.add(new JLabel("max:"));
        controls.add(tfMax);
        controls.add(btnSetRange);
        controls.add(btnResetAuto);

        f.setLayout(new BorderLayout());
        f.add(viewer, BorderLayout.CENTER);
        f.add(controls, BorderLayout.SOUTH);

        f.setSize(820, 820);
        f.setLocationByPlatform(true);
        f.setVisible(true);
        return f;
    }
}

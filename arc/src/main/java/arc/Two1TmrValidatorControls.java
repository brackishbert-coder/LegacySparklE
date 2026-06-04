package arc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Lag-aware partial correlation validator for TWO1 vs TMR.
 *
 * For each lag L (in windows):
 *   x = two1[t]
 *   y = tmr[t+L]
 *
 * Residualize x using controls at time t,
 * residualize y using controls at time t+L,
 * then correlate residuals.
 *
 * Also includes a circular-shift null test over |corr| peak across lags.
 */
public final class Two1TmrValidatorControls {

    public static final class Window {
        public final long tMs;
        public final long windowMs;

        // Raw signals (not normalized)
        public final double tmr;         // RAW TMR in [0..1]
        public final double two1EffRate; // RAW events/sec

        // Controls (use consistent versions!)
        public final double entropy; // usually entropyDisplay or entropyRaw; pick one and stick with it
        public final double churn01;
        public final double dCurv01;
		private double curv01;

        public Window(long tMs, long windowMs,
                      double tmr,
                      double two1EffRate,
                      double entropy,
                      double churn01,
                      double dCurv01, double curv01) {
            this.tMs = tMs;
            this.windowMs = windowMs;
            this.tmr = tmr;
            this.two1EffRate = two1EffRate;
            this.entropy = entropy;
            this.churn01 = churn01;
            this.dCurv01 = dCurv01;
			this.curv01 = curv01;
        }
    }

    private final int maxWindows;
    private final int reportEvery;
    private final int maxLag;       // in windows
    private final int nullTrials;
    private final Random rng = new Random(1);

    // Optional burst logic (kept if you want it later; safe if unused)
    private final double burstThresholdEventsPerSec;
    private final int burstHalfWidthWindows;

    private final List<Window> windows = new ArrayList<>();
    private static final class ResidualResult {
        final double[] r;
        final boolean fallback;
        final double r2;
        ResidualResult(double[] r, boolean fallback, double r2) {
            this.r = r; this.fallback = fallback; this.r2 = r2;
        }
    }
    private final Two1AlignedTmrPlotPanel alignedPlot;
    private final int pre;   // windows before τ=0
    private final int post;  // windows after τ=0

    public Two1TmrValidatorControls(int maxWindows,
            int reportEvery,
            int maxLag,
            int nullTrials,
            double burstThresholdEventsPerSec,
            int burstHalfWidthWindows,
            Two1AlignedTmrPlotPanel alignedPlot,
            int pre,
            int post) {
        this.maxWindows = Math.max(50, maxWindows);
        this.reportEvery = Math.max(5, reportEvery);
        this.maxLag = Math.max(1, maxLag);
        this.nullTrials = Math.max(50, nullTrials);
        this.burstThresholdEventsPerSec = burstThresholdEventsPerSec;
        this.burstHalfWidthWindows = Math.max(1, burstHalfWidthWindows);
        this.alignedPlot = alignedPlot;
        this.pre = Math.max(1, pre);
        this.post = Math.max(1, post);
    }

    public void addWindow(Window w) {
        synchronized (windows) {
            windows.add(w);
            if (windows.size() > maxWindows) {
                windows.remove(0);
            }
        }
    }
    private static void debugSeries(String name, double[] a) {
        int n = a.length;
        double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
        int nNan = 0, nZero = 0;
        double s = 0.0, s2 = 0.0;

        for (double v : a) {
            if (!Double.isFinite(v)) { nNan++; continue; }
            if (v == 0.0) nZero++;
            mn = Math.min(mn, v);
            mx = Math.max(mx, v);
            s += v;
            s2 += v * v;
        }

        int nf = n - nNan;
        double mean = (nf > 0) ? (s / nf) : Double.NaN;
        double var = (nf > 0) ? (s2 / nf - mean * mean) : Double.NaN;
        double sd = (var > 0) ? Math.sqrt(var) : 0.0;

        System.out.printf("[diag] %-12s n=%d finite=%d nan=%d min=%.6f max=%.6f mean=%.6f sd=%.6g zero%%=%.2f%n",
                name, n, nf, nNan, mn, mx, mean, sd, (100.0 * nZero / Math.max(1, n)));
    }

    private static double std(double[] a) {
        double s = 0, s2 = 0;
        int n = 0;
        for (double v : a) {
            if (!Double.isFinite(v)) continue;
            s += v; s2 += v*v; n++;
        }
        if (n <= 1) return 0.0;
        double m = s / n;
        double var = s2 / n - m * m;
        return var > 0 ? Math.sqrt(var) : 0.0;
    }

public void maybeReport() {
    final List<Window> snap;
    synchronized (windows) {
        int n0 = windows.size();
        if (n0 < reportEvery) return;
        if ((n0 % reportEvery) != 0) return;
        snap = new ArrayList<>(windows);
    }

    int n = snap.size();

    double[] tmr = new double[n];
    double[] two1 = new double[n];
    double[] entropy = new double[n];
    double[] churn = new double[n];
    double[] dCurv = new double[n];

    for (int i = 0; i < n; i++) {
        Window w = snap.get(i);
        tmr[i] = w.tmr;
        two1[i] = w.two1EffRate;
        entropy[i] = w.entropy;
        churn[i] = w.churn01;
        dCurv[i] = w.dCurv01;
    }

    // diagnostics first
    debugSeries("tmr", tmr);
    debugSeries("two1EffRate", two1);
    debugSeries("entropy", entropy);
    debugSeries("churn01", churn);
    debugSeries("dCurv01", dCurv);

    // if raw is flat, stop early with a clear message
    if (std(two1) < 1e-9 || std(tmr) < 1e-9) {
        System.out.println("[Two1TmrValidatorControls] ABORT: RAW variance collapse " +
                "(two1Std=" + std(two1) + ", tmrStd=" + std(tmr) + ")");
        return;
    }

    LagCorr rawBest = bestLagCorrRaw(two1, tmr, maxLag);
    LagCorr partialBest = bestLagCorrLagAwarePartial(two1, tmr, entropy, churn, dCurv, maxLag);
    double pPartial = peakAbsCorrNullP(two1, tmr, entropy, churn, dCurv, maxLag, nullTrials);

    // ... your existing prints ...
}

    // -------------------------
    // Core: raw best lag corr
    // -------------------------
    private static final class LagCorr {
        final int lag;
        final double r;
        LagCorr(int lag, double r) { this.lag = lag; this.r = r; }
    }

private static LagCorr bestLagCorrRaw(double[] x, double[] y, int maxLag) {
    double bestAbs = -1.0;
    int bestLag = 0;
    double bestR = Double.NaN;

    for (int lag = -maxLag; lag <= maxLag; lag++) {
        Align a = alignForLag(lag, x.length);
        double sx = stdSlice(x, a.x0, a.len);
        double sy = stdSlice(y, a.y0, a.len);
        if (sx < 1e-9 || sy < 1e-9) {
            // uncomment if needed:
             System.out.printf("[diag] lag=%+d sliceStd x=%.3g y=%.3g len=%d%n", lag, sx, sy, a.len);
        }

        double r = corrSlice(x, a.x0, y, a.y0, a.len);
        if (!Double.isFinite(r)) continue;

        double abs = Math.abs(r);
        if (abs > bestAbs) { bestAbs = abs; bestLag = lag; bestR = r; }
    }
    return new LagCorr(bestLag, bestR);
}

private static double stdSlice(double[] a, int start, int len) {
    double s=0, s2=0; int n=0;
    for (int i=0;i<len;i++) { double v=a[start+i]; if(!Double.isFinite(v)) continue; s+=v; s2+=v*v; n++; }
    if (n<=1) return 0;
    double m=s/n;
    double var=s2/n - m*m;
    return var>0?Math.sqrt(var):0;
}


    static final class Align {
        final int x0, y0, len;
        Align(int x0, int y0, int len) { this.x0 = x0; this.y0 = y0; this.len = len; }
    }

    /** x = two1, y = tmr. lag<0 => two1 leads; lag>0 => tmr leads. */
    /** x = two1[t], y = tmr[t+lag]. */
    static Align alignForLag(int lag, int n) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0");

        int x0, y0;
        int len;

        if (lag >= 0) {
            x0 = 0;
            y0 = lag;
            len = n - lag;
        } else {
            x0 = -lag;
            y0 = 0;
            len = n + lag; // lag negative
        }

        if (len <= 0) return new Align(0, 0, 0); // no overlap
        // (optional) if you want to guarantee indices are valid even for weird lag:
        if (x0 < 0 || y0 < 0 || x0 + len > n || y0 + len > n) {
            throw new IllegalStateException("computed alignment out of bounds");
        }

        return new Align(x0, y0, len);
    }


    public List<Window> getWindowsSnapshot() {
        synchronized (windows) {
            return new ArrayList<>(windows);
        }
    }


    static double corrSlice(double[] x, int x0, double[] y, int y0, int len) {
        double mx = 0, my = 0;
        for (int i = 0; i < len; i++) { mx += x[x0+i]; my += y[y0+i]; }
        mx /= len; my /= len;

        double sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < len; i++) {
            double dx = x[x0+i] - mx;
            double dy = y[y0+i] - my;
            sxx += dx*dx; syy += dy*dy; sxy += dx*dy;
        }
        double denom = Math.sqrt(sxx*syy);
        if (denom < 1e-12) return Double.NaN;
        return sxy / denom;
    }

    // ---------- main function ----------
    /**
     * Find lag in [-maxLag..+maxLag] that maximizes |partialCorr|.
     *
     * Lag convention:
     *  - lag < 0 : TWO1 leads TMR by |lag| windows (TWO1 earlier)
     *  - lag > 0 : TMR leads TWO1 by lag windows
     *
     * Controls are applied lag-aware:
     *  - when comparing aligned slices (x,y), controls for x and y are sliced with the same indices.
     */
    static LagCorr bestLagCorrLagAwarePartial(
            double[] two1, double[] tmr,
            double[] entropy, double[] churn, double[] dCurv,
            int maxLag
    ) {
        int n = Math.min(two1.length, tmr.length);
        n = Math.min(n, entropy.length);
        n = Math.min(n, churn.length);
        n = Math.min(n, dCurv.length);

        int L = Math.min(maxLag, n - 8);

        LagCorr best = new LagCorr(0, Double.NaN);
        double bestAbs = -1;

 for (int lag = -L; lag <= L; lag++) {
    Align a = alignForLag(lag, n);
    if (a.len < 16) continue;

    // x = TWO1[t], y = TMR[t+lag]
    double[] x = slice(two1, a.x0, a.len);
    double[] y = slice(tmr,  a.y0, a.len);

    // controls aligned to x-time (t)
    double[] ex = slice(entropy, a.x0, a.len);
    double[] cx = slice(churn,   a.x0, a.len);
    double[] dx = slice(dCurv,   a.x0, a.len);

    // controls aligned to y-time (t+lag)
    double[] ey = slice(entropy, a.y0, a.len);
    double[] cy = slice(churn,   a.y0, a.len);
    double[] dy = slice(dCurv,   a.y0, a.len);

    // residualize each series against its own time-aligned controls
    double[] xr = residualizeAgainstControls(x, ex, cx, dx);
    double[] yr = residualizeAgainstControls(y, ey, cy, dy);

    double r = corr(xr, yr);
    if (!Double.isFinite(r)) continue;

    double ar = Math.abs(r);
    if (ar > bestAbs) { bestAbs = ar; best = new LagCorr(lag, r); }
}


        return bestAbs < 0 ? new LagCorr(0, Double.NaN) : best;
    }

 // -------------------------
 // Helpers: slicing + OLS residualization
 // -------------------------

 private static double[] slice(double[] a, int start, int len) {
     if (a == null) throw new IllegalArgumentException("slice: array is null");
     if (start < 0 || len < 0 || start + len > a.length) {
         throw new IllegalArgumentException(
                 "slice: start=" + start + " len=" + len + " a.length=" + a.length);
     }
     double[] out = new double[len];
     System.arraycopy(a, start, out, 0, len);
     return out;
 }

 /**
  * Residualize y against controls (with intercept):
  *   y ~ b0 + b1*c1 + b2*c2 + b3*c3
  * Returns residuals r = y - yHat.
  *
  * Notes:
  * - Assumes arrays are same length.
  * - If the linear system is singular/ill-conditioned, returns demeaned y as a safe fallback.
  */
 private static double[] residualizeAgainstControls(double[] y, double[] c1, double[] c2, double[] c3) {
     int n = y.length;
     if (c1.length != n || c2.length != n || c3.length != n) {
         throw new IllegalArgumentException("residualize: length mismatch");
     }

     // Build normal equations: (X^T X) beta = (X^T y)
     // X columns: [1, c1, c2, c3] => 4 parameters.
     double[][] A = new double[4][4];
     double[] b = new double[4];

     for (int i = 0; i < n; i++) {
         double x0 = 1.0;
         double x1 = c1[i];
         double x2 = c2[i];
         double x3 = c3[i];
         double yi = y[i];

         // X^T y
         b[0] += x0 * yi;
         b[1] += x1 * yi;
         b[2] += x2 * yi;
         b[3] += x3 * yi;

         // X^T X (symmetric)
         A[0][0] += x0 * x0;
         A[0][1] += x0 * x1;
         A[0][2] += x0 * x2;
         A[0][3] += x0 * x3;

         A[1][1] += x1 * x1;
         A[1][2] += x1 * x2;
         A[1][3] += x1 * x3;

         A[2][2] += x2 * x2;
         A[2][3] += x2 * x3;

         A[3][3] += x3 * x3;
     }
     // fill symmetry
     A[1][0] = A[0][1];
     A[2][0] = A[0][2];
     A[3][0] = A[0][3];
     A[2][1] = A[1][2];
     A[3][1] = A[1][3];
     A[3][2] = A[2][3];

     // Solve for beta
     double[] beta = solve4x4(A, b);
     if (beta == null) {
         // Fallback: remove mean only (still a valid "control" in the weak sense)
         double mu = mean(y);
         double[] r = new double[n];
         for (int i = 0; i < n; i++) r[i] = y[i] - mu;
         return r;
     }

     // residuals
     double[] r = new double[n];
     for (int i = 0; i < n; i++) {
         double yhat = beta[0] + beta[1] * c1[i] + beta[2] * c2[i] + beta[3] * c3[i];
         r[i] = y[i] - yhat;
     }
     return r;
 }

 /**
  * Solve a 4x4 linear system A x = b via Gauss-Jordan elimination.
  * Returns null if singular / unstable.
  */
 private static double[] solve4x4(double[][] A, double[] b) {
     // Augmented matrix [A | b]
     double[][] M = new double[4][5];
     for (int i = 0; i < 4; i++) {
         System.arraycopy(A[i], 0, M[i], 0, 4);
         M[i][4] = b[i];
     }

     final double EPS = 1e-12;

     for (int col = 0; col < 4; col++) {
         // pivot row
         int piv = col;
         double best = Math.abs(M[col][col]);
         for (int r = col + 1; r < 4; r++) {
             double v = Math.abs(M[r][col]);
             if (v > best) { best = v; piv = r; }
         }
         if (best < EPS) return null;

         // swap
         if (piv != col) {
             double[] tmp = M[piv];
             M[piv] = M[col];
             M[col] = tmp;
         }

         // normalize pivot row
         double diag = M[col][col];
         for (int c = col; c < 5; c++) M[col][c] /= diag;

         // eliminate other rows
         for (int r = 0; r < 4; r++) {
             if (r == col) continue;
             double f = M[r][col];
             if (Math.abs(f) < EPS) continue;
             for (int c = col; c < 5; c++) {
                 M[r][c] -= f * M[col][c];
             }
         }
     }

     return new double[] { M[0][4], M[1][4], M[2][4], M[3][4] };
 }

 private static double mean(double[] a) {
     double s = 0.0;
     for (double v : a) s += v;
     return s / Math.max(1, a.length);
 }



    // ---------- residualization ----------
    /**
     * Returns residuals of y after OLS regression on [intercept + X].
     * If X has 0 columns, this is just demean(y).
     */
    private static double[] residualizeWithIntercept(double[] y, double[][] X) {
        int n = y.length;
        int k = (X == null) ? 0 : (X.length == 0 ? 0 : X[0].length);

        // If no controls: just demean
        if (k == 0) {
            double mean = 0.0;
            for (double v : y) mean += v;
            mean /= Math.max(1, n);

            double[] r = new double[n];
            for (int i = 0; i < n; i++) r[i] = y[i] - mean;
            return r;
        }

        // Design matrix A: [n][1+k]
        double[][] A = new double[n][1 + k];
        for (int i = 0; i < n; i++) {
            A[i][0] = 1.0;
            for (int j = 0; j < k; j++) A[i][1 + j] = X[i][j];
        }

        // Solve beta = (A^T A)^-1 A^T y
        double[] beta = olsSolve(A, y);
        if (beta == null) {
            // fallback: demean
            double mean = 0.0;
            for (double v : y) mean += v;
            mean /= Math.max(1, n);

            double[] r = new double[n];
            for (int i = 0; i < n; i++) r[i] = y[i] - mean;
            return r;
        }

        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            double yhat = beta[0];
            for (int j = 0; j < k; j++) yhat += beta[1 + j] * X[i][j];
            r[i] = y[i] - yhat;
        }
        return r;
    }

    // ---------- OLS solver via normal equations (small k) ----------
    /**
     * Returns beta or null if singular/failed.
     * A is [n][p], y is [n].
     */
    private static double[] olsSolve(double[][] A, double[] y) {
        int n = A.length;
        int p = A[0].length;

        // Compute AtA and AtY
        double[][] AtA = new double[p][p];
        double[] AtY = new double[p];

        for (int i = 0; i < n; i++) {
            double[] ai = A[i];
            double yi = y[i];

            for (int j = 0; j < p; j++) {
                AtY[j] += ai[j] * yi;
                for (int k = 0; k < p; k++) {
                    AtA[j][k] += ai[j] * ai[k];
                }
            }
        }

        return solveLinearSystem(AtA, AtY);
    }

    /**
     * Solve M b = v using Gaussian elimination with partial pivoting.
     * Returns null if singular.
     */
    private static double[] solveLinearSystem(double[][] M, double[] v) {
        int n = v.length;
        double[][] A = new double[n][n];
        double[] b = new double[n];

        for (int i = 0; i < n; i++) {
            System.arraycopy(M[i], 0, A[i], 0, n);
            b[i] = v[i];
        }

        // Elimination
        for (int col = 0; col < n; col++) {
            // Pivot
            int pivot = col;
            double best = Math.abs(A[col][col]);
            for (int r = col + 1; r < n; r++) {
                double val = Math.abs(A[r][col]);
                if (val > best) {
                    best = val;
                    pivot = r;
                }
            }
            if (best < 1e-12) return null; // singular

            if (pivot != col) {
                double[] tmp = A[pivot]; A[pivot] = A[col]; A[col] = tmp;
                double tb = b[pivot]; b[pivot] = b[col]; b[col] = tb;
            }

            // Normalize pivot row
            double piv = A[col][col];
            for (int c = col; c < n; c++) A[col][c] /= piv;
            b[col] /= piv;

            // Eliminate below
            for (int r = col + 1; r < n; r++) {
                double f = A[r][col];
                if (f == 0.0) continue;
                for (int c = col; c < n; c++) A[r][c] -= f * A[col][c];
                b[r] -= f * b[col];
            }
        }

        // Back-substitution
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            for (int j = i + 1; j < n; j++) s -= A[i][j] * x[j];
            x[i] = s; // diag is 1.0
        }
        return x;
    }




    /**
     * Correlate residual(two1[t] | controls[t]) with residual(tmr[t+lag] | controls[t+lag]).
     */
    private static double corrLagAwarePartial(double[] two1, double[] tmr,
                                             double[] entropy, double[] churn, double[] dCurv,
                                             int lag) {
        int n = two1.length;
        int i0 = Math.max(0, -lag);
        int i1 = Math.min(n, n - lag); // exclusive

        int m = i1 - i0;
        if (m < 12) return Double.NaN; // too short for stability

        double[] x = new double[m];
        double[] y = new double[m];

        double[][] cx = new double[m][3];
        double[][] cy = new double[m][3];

        for (int k = 0; k < m; k++) {
            int i = i0 + k;
            int j = i + lag;

            x[k] = two1[i];
            y[k] = tmr[j];

            cx[k][0] = entropy[i];
            cx[k][1] = churn[i];
            cx[k][2] = dCurv[i];

            cy[k][0] = entropy[j];
            cy[k][1] = churn[j];
            cy[k][2] = dCurv[j];
        }

        double[] rx = residualizeWithIntercept(x, cx);
        double[] ry = residualizeWithIntercept(y, cy);

        return corr(rx, ry);
    }

    // --------------------------------------------
    // Null test: circular shift of TWO1 only
    // --------------------------------------------
    private double peakAbsCorrNullP(double[] two1, double[] tmr,
                                   double[] entropy, double[] churn, double[] dCurv,
                                   int maxLag, int trials) {
        LagCorr obs = bestLagCorrLagAwarePartial(two1, tmr, entropy, churn, dCurv, maxLag);
        double obsAbs = Double.isNaN(obs.r) ? Double.NaN : Math.abs(obs.r);
        if (Double.isNaN(obsAbs)) return 1.0;

        int n = two1.length;

        int ge = 0; // count of null >= observed
        int valid = 0;

        for (int t = 0; t < trials; t++) {
            int shift = 1 + rng.nextInt(Math.max(1, n - 1)); // non-zero shift
            double[] two1s = circularShift(two1, shift);

         // Controls stay FIXED in time (aligned to TMR timeline).
         LagCorr nul = bestLagCorrLagAwarePartial(two1s, tmr, entropy, churn, dCurv, maxLag);

            if (Double.isNaN(nul.r)) continue;

            valid++;
            if (Math.abs(nul.r) >= obsAbs) ge++;
        }

        if (valid <= 0) return 1.0;
        // add-one smoothing
        return (ge + 1.0) / (valid + 1.0);
    }

    private static double[] circularShift(double[] a, int shift) {
        int n = a.length;
        double[] out = new double[n];
        int s = ((shift % n) + n) % n;
        for (int i = 0; i < n; i++) {
            int j = i + s;
            if (j >= n) j -= n;
            out[j] = a[i];
        }
        return out;
    }

    // -------------------------
    // Basic correlation helpers
    // -------------------------
    private static double corrAligned(double[] x, double[] y, int lag) {
        int n = x.length;
        int i0 = Math.max(0, -lag);
        int i1 = Math.min(n, n - lag);
        int m = i1 - i0;
        if (m < 12) return Double.NaN;

        double[] a = new double[m];
        double[] b = new double[m];
        for (int k = 0; k < m; k++) {
            int i = i0 + k;
            int j = i + lag;
            a[k] = x[i];
            b[k] = y[j];
        }
        return corr(a, b);
    }

    private static double corr(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        if (n < 3) return Double.NaN;

        double ma = 0.0, mb = 0.0;
        for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; }
        ma /= n; mb /= n;

        double va = 0.0, vb = 0.0, cov = 0.0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - ma;
            double db = b[i] - mb;
            va += da * da;
            vb += db * db;
            cov += da * db;
        }

        // Prevent your -Infinity / divide-by-zero
        double eps = 1e-12;
        if (va <= eps || vb <= eps) return Double.NaN;

        return cov / Math.sqrt(va * vb);
    }



    /**
     * Solve A x = b for 4x4 using Gaussian elimination with partial pivoting.
     * Returns null if singular/unstable.
     */
    private static double[] solveLinearSystem4(double[][] A, double[] b) {
        int n = 4;
        double[][] M = new double[n][n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            y[i] = b[i];
        }

        double eps = 1e-12;

        // Forward elimination
        for (int col = 0; col < n; col++) {
            // Pivot
            int piv = col;
            double best = Math.abs(M[col][col]);
            for (int r = col + 1; r < n; r++) {
                double v = Math.abs(M[r][col]);
                if (v > best) { best = v; piv = r; }
            }
            if (best < eps) return null;

            if (piv != col) {
                double[] tmp = M[piv]; M[piv] = M[col]; M[col] = tmp;
                double ty = y[piv]; y[piv] = y[col]; y[col] = ty;
            }

            // Eliminate
            double diag = M[col][col];
            for (int r = col + 1; r < n; r++) {
                double f = M[r][col] / diag;
                if (Math.abs(f) < eps) continue;
                y[r] -= f * y[col];
                for (int c = col; c < n; c++) {
                    M[r][c] -= f * M[col][c];
                }
            }
        }

        // Back substitution
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = y[r];
            for (int c = r + 1; c < n; c++) s -= M[r][c] * x[c];
            double diag = M[r][r];
            if (Math.abs(diag) < eps) return null;
            x[r] = s / diag;
        }
        return x;
    }
}

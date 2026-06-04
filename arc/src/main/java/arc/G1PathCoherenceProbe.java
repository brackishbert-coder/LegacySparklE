package arc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * G1: Path coherence on SOM grid (graph geodesic).
 *
 * Coherence C = D / (L + eps)
 *   D = geodesic distance from start to end in window
 *   L = sum of step distances in window
 *
 * We compare TWO1 event windows vs matched-null windows (same BMU).
 */
public final class G1PathCoherenceProbe implements TelemetryObserver, EventObserver {
    private final int w, h;
    private final int window; // in telemetry ticks (metrics windows), not audio frames
    private final Random rng = new Random(1);

    // ring buffer of BMUs per telemetry tick
    private final int[] bmus;
    private final long[] ts;
    private int head = 0;
    private int count = 0;

    // results
    private final List<Double> eventC = new ArrayList<>();
    private final List<Double> controlC = new ArrayList<>();

    // to match-null: keep candidate indices where BMU matches
    public G1PathCoherenceProbe(int w, int h, int historyLen, int window) {
        this.w = w;
        this.h = h;
        this.window = window;
        this.bmus = new int[historyLen];
        this.ts = new long[historyLen];
        for (int i = 0; i < historyLen; i++) bmus[i] = -1;
    }

    @Override
    public void onTelemetry(ArcTelemetry t) {
        push(t.nowMs, t.currentBMU);
    }

    @Override
    public void onEvent(ArcEvent e) {
        if (e.type != ArcEventType.TWO1_TRIGGERED) return;

        // Event coherence around NOW (centered at latest tick)
        int idxNow = indexOfLatest();
        if (idxNow < 0) return;

        int bmuNow = bmuAt(idxNow);
        if (bmuNow < 0) return;

        Double cEvent = coherenceCenteredAt(idxNow);
        if (cEvent == null) return;
        eventC.add(cEvent);

        // Matched-null: pick another time with same BMU, not too close
        Integer ctrlIdx = pickControlIndexSameBMU(bmuNow, idxNow);
        if (ctrlIdx != null) {
            Double cCtrl = coherenceCenteredAt(ctrlIdx);
            if (cCtrl != null) controlC.add(cCtrl);
        }

        // Optional: print running stats occasionally
        if ((eventC.size() % 25) == 0) {
            System.out.printf("[G1] events=%d controls=%d  meanC(event)=%.3f meanC(ctrl)=%.3f%n",
                    eventC.size(), controlC.size(), mean(eventC), mean(controlC));
        }
    }

    private void push(long tMs, int bmu) {
        bmus[head] = bmu;
        ts[head] = tMs;
        head = (head + 1) % bmus.length;
        if (count < bmus.length) count++;
    }

    private int indexOfLatest() {
        if (count == 0) return -1;
        int idx = head - 1;
        if (idx < 0) idx += bmus.length;
        return idx;
    }

    private int bmuAt(int idx) { return bmus[idx]; }

    private Double coherenceCenteredAt(int centerIdx) {
        // Need window on both sides
        int needed = 2 * window + 1;
        if (count < needed) return null;

        int startIdx = offsetIndex(centerIdx, -window);
        int endIdx   = offsetIndex(centerIdx, +window);

        int bStart = bmuAt(startIdx);
        int bEnd   = bmuAt(endIdx);
        if (bStart < 0 || bEnd < 0) return null;

        int xS = bStart % w, yS = bStart / w;
        int xE = bEnd % w, yE = bEnd / w;

        double D = geoDist(xS, yS, xE, yE);

        double L = 0.0;
        int prev = bStart;

        // Walk indices from start -> end
        int idx = startIdx;
        for (int k = 0; k < 2 * window; k++) {
            int nextIdx = offsetIndex(idx, +1);
            int cur = bmuAt(nextIdx);
            if (prev < 0 || cur < 0) return null;

            int x1 = prev % w, y1 = prev / w;
            int x2 = cur % w,  y2 = cur / w;
            L += geoDist(x1, y1, x2, y2);

            prev = cur;
            idx = nextIdx;
        }

        double eps = 1e-9;
        return D / (L + eps);
    }

    private Integer pickControlIndexSameBMU(int bmu, int avoidIdx) {
        // Collect candidate indices where BMU matches and far from avoidIdx
        int tries = 50;
        for (int t = 0; t < tries; t++) {
            int back = window + 3 + rng.nextInt(Math.max(1, count - (2 * window + 6)));
            int idx = offsetIndex(avoidIdx, -back);
            if (bmuAt(idx) == bmu) return idx;
        }

        // fallback scan
        for (int i = 0; i < count; i++) {
            int idx = offsetIndex(avoidIdx, -(i + window + 3));
            if (bmuAt(idx) == bmu) return idx;
        }
        return null;
    }

    private int offsetIndex(int idx, int delta) {
        int out = idx + delta;
        while (out < 0) out += bmus.length;
        while (out >= bmus.length) out -= bmus.length;
        return out;
    }

    private static double geoDist(int x1, int y1, int x2, int y2) {
        // 4-neighborhood geodesic
        return Math.abs(x2 - x1) + Math.abs(y2 - y1);
    }

    private static double mean(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return 0.0;
        double s = 0.0;
        for (double v : xs) s += v;
        return s / xs.size();
    }

    // You can expose these at shutdown to print summary.
    public int eventCount() { return eventC.size(); }
    public int controlCount() { return controlC.size(); }
    public double meanEventC() { return mean(eventC); }
    public double meanControlC() { return mean(controlC); }
}

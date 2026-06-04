package arc;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ArcEvent {
    public final long nowMs;
    public final ArcEventType type;
    public final String reason;
    public final Map<String, Double> ctx;

    public ArcEvent(long nowMs, ArcEventType type, String reason, Map<String, Double> ctx) {
        this.nowMs = nowMs;
        this.type = type;
        this.reason = reason == null ? "" : reason;
        this.ctx = ctx == null ? Collections.emptyMap() : Collections.unmodifiableMap(ctx);
    }

    public static Map<String, Double> ctx(Object... kv) {
        if (kv == null || kv.length == 0) return Collections.emptyMap();
        if ((kv.length & 1) == 1) throw new IllegalArgumentException("ctx requires even number of args");
        HashMap<String, Double> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            String k = String.valueOf(kv[i]);
            Object v = kv[i + 1];
            double d = (v instanceof Number) ? ((Number) v).doubleValue() : Double.NaN;
            m.put(k, d);
        }
        return m;
    }
}

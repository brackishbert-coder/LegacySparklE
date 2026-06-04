package arc;

import java.util.concurrent.CopyOnWriteArrayList;

public final class ArcBus {
    private final CopyOnWriteArrayList<TelemetryObserver> telemetryObservers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EventObserver> eventObservers = new CopyOnWriteArrayList<>();

    public void addTelemetryObserver(TelemetryObserver o) {
        if (o != null) telemetryObservers.addIfAbsent(o);
    }

    public void addEventObserver(EventObserver o) {
        if (o != null) eventObservers.addIfAbsent(o);
    }

    public void emitTelemetry(ArcTelemetry t) {
        for (TelemetryObserver o : telemetryObservers) {
            try { o.onTelemetry(t); } catch (Throwable ignored) {}
        }
    }

    public void emitEvent(ArcEvent e) {
        for (EventObserver o : eventObservers) {
            try { o.onEvent(e); } catch (Throwable ignored) {}
        }
    }
}

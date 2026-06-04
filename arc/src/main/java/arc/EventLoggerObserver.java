package arc;

public final class EventLoggerObserver implements EventObserver {
    @Override
    public void onEvent(ArcEvent e) {
        System.out.println("[EVENT] " + e.type + " t=" + e.nowMs + " reason=" + e.reason + " ctx=" + e.ctx);
    }
}

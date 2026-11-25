package pl.mtech.mremora.viewer;

import java.time.Duration;
import pl.mtech.mremora.agent.AgentUi;

/** {@link AgentUi} that remembers the shown code and lets a test press "End session". Thread-safe. */
final class RecordingAgentUi implements AgentUi {

    private String lastCode;
    private Runnable lastOnEnd;

    @Override
    public synchronized void showCode(String code) {
        lastCode = code;
    }

    @Override
    public void showLocked(Duration remaining) {
    }

    @Override
    public synchronized void showSession(String viewerLabel, Runnable onEndRequested) {
        lastOnEnd = onEndRequested;
    }

    synchronized String lastCode() {
        return lastCode;
    }

    synchronized boolean sessionShown() {
        return lastOnEnd != null;
    }

    /** Presses "End session": runs the callback of the latest {@code showSession}. */
    void endSession() {
        Runnable onEnd;
        synchronized (this) {
            onEnd = lastOnEnd;
        }
        if (onEnd == null) {
            throw new IllegalStateException("No session was shown");
        }
        onEnd.run();
    }
}

package pl.mtech.mremora.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AgentUi} that records calls as strings ({@code code 123456}, {@code locked}, {@code session 127.0.0.1}).
 * Thread-safe: the server calls it from its event loop while the test reads it.
 */
final class RecordingUi implements AgentUi {

    private final List<String> calls = new ArrayList<>();
    private String lastCode;
    private Runnable lastOnEnd;
    private boolean failShowSession;

    @Override
    public synchronized void showCode(String code) {
        lastCode = code;
        calls.add("code " + code);
    }

    @Override
    public synchronized void showLocked(Duration remaining) {
        calls.add("locked");
    }

    @Override
    public synchronized void showSession(String viewerLabel, Runnable onEndRequested) {
        lastOnEnd = onEndRequested;
        calls.add("session " + viewerLabel);
        if (failShowSession) {
            throw new IllegalStateException("showSession failed on purpose");
        }
    }

    /** Makes every later {@code showSession} throw after recording the call. */
    synchronized void failShowSession() {
        failShowSession = true;
    }

    synchronized List<String> calls() {
        return List.copyOf(calls);
    }

    synchronized String lastCode() {
        return lastCode;
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

    /** Polls until a call starting with {@code prefix} was recorded. */
    boolean awaitCall(String prefix, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (calls().stream().anyMatch(c -> c.startsWith(prefix))) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
    }
}

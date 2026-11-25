package pl.mtech.mremora.agent;

import java.time.Duration;

/**
 * What the agent shows to the local user. Methods are called from the server's event-loop thread, so
 * implementations must not block.
 */
public interface AgentUi {

    /** Shows the pairing code and that the agent waits for a viewer. */
    void showCode(String code);

    /** Shows that pairing is locked after too many wrong codes. */
    void showLocked(Duration remaining);

    /**
     * Shows an active session with a way for the local user to end it.
     *
     * @param onEndRequested called at most once per {@code showSession} call, from any thread; must be
     *     a fast, non-throwing signal. A {@link RuntimeException} it throws is logged at ERROR.
     */
    void showSession(String viewerLabel, Runnable onEndRequested);
}

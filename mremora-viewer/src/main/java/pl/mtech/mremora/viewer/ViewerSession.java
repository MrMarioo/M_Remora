package pl.mtech.mremora.viewer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.ScreenInfo;

/**
 * One viewer session without UI: an {@link AgentConnection} whose frames are decoded by a {@link FrameDecoder} and
 * whose mouse input comes from an {@link InputTranslator}.
 *
 * <p>All {@link Listener} calls go through the {@code ui} executor; the UI uses {@link #input()} only on its own thread
 * and never waits for the network.
 */
final class ViewerSession {

    private static final String DISCONNECT_THREAD = "mremora-disconnect";

    /** Events of the session, called through the {@code ui} executor given to {@link #open}. */
    interface Listener {

        /** A decoded frame is ready to be shown. */
        void onFrame(DecodedFrame frame);

        /**
         * The session ended; called exactly once. No {@link #onFrame} follows it only when {@code ui} is a
         * single-threaded FIFO executor such as {@code Platform::runLater}.
         */
        void onClosed(String reason);
    }

    private final AgentConnection connection;
    private final InputTranslator input;
    private CompletableFuture<Void> disconnected;

    private ViewerSession(AgentConnection connection) {
        this.connection = connection;
        this.input = new InputTranslator(connection::sendInput);
    }

    /**
     * Connects to an agent; see {@link AgentConnection#connect} for the handshake.
     *
     * @param ui runs the listener calls, normally {@code Platform::runLater}
     * @return completes with the established session, or fails with the same exception as
     *     {@link AgentConnection#connect} (then the listener is never called)
     */
    static CompletableFuture<ViewerSession> open(String host, int port, String pairingCode, Executor ui,
                                                 Listener listener) {
        Objects.requireNonNull(ui, "ui");
        Objects.requireNonNull(listener, "listener");
        FrameDecoder decoder = new FrameDecoder(ui, listener::onFrame);
        AgentConnection.Listener connectionListener = new AgentConnection.Listener() {
            @Override
            public void onFrame(FrameUpdate frame) {
                decoder.offer(frame);
            }

            @Override
            public void onClosed(String reason) {
                // Closed before onClosed is queued: no frame delivery runs after it on a FIFO executor.
                decoder.close();
                ui.execute(() -> listener.onClosed(reason));
            }
        };
        CompletableFuture<AgentConnection> connecting;
        try {
            connecting = AgentConnection.connect(host, port, pairingCode, connectionListener);
        } catch (RuntimeException e) {
            decoder.close();
            throw e;
        }
        CompletableFuture<ViewerSession> result = new CompletableFuture<>();
        connecting.whenComplete((connection, error) -> {
            if (error != null) {
                decoder.close();
                result.completeExceptionally(error);
            } else if (!result.complete(new ViewerSession(connection))) {
                // The caller gave up waiting (e.g. cancelled): nobody owns this session. The listener still gets
                // onClosed("closed by viewer"); the UI never cancels, so this is accepted.
                connection.close();
            }
        });
        return result;
    }

    /** Geometry of the agent's screen, in physical pixels. */
    ScreenInfo screenInfo() {
        return connection.screenInfo();
    }

    /** Mouse input for the agent; UI thread only. Input after the session ended is dropped. */
    InputTranslator input() {
        return input;
    }

    /**
     * Releases held buttons (on the calling thread, which must be the UI thread), then closes the connection on a
     * virtual thread. Returns at once; idempotent.
     *
     * @return completes once the connection and its thread are closed; the same future on every call
     */
    synchronized CompletableFuture<Void> disconnect() {
        if (disconnected == null) {
            input.releaseAll();
            CompletableFuture<Void> done = new CompletableFuture<>();
            Thread.ofVirtual().name(DISCONNECT_THREAD).start(() -> {
                try {
                    connection.close();
                    done.complete(null);
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
            disconnected = done;
        }
        return disconnected;
    }
}

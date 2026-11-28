package pl.mtech.mremora.viewer;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.StreamConfig;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.TransportState;
import pl.mtech.mremora.protocol.udp.CompleteRect;

/**
 * One viewer session without UI: an {@link AgentConnection} whose frames are decoded by a {@link FrameDecoder}
 * (protocol v1) or a {@link TileDecoder} (v2, and v3 with {@code TransportState(TCP)}) and whose mouse input comes
 * from an {@link InputTranslator}. On v2 a tile frame the decoder cannot read ends the session with
 * {@code Bye("protocol error")}.
 *
 * <p>On v3 with {@code TransportState(UDP)} the picture comes as UDP rectangles, decoded by a {@link UdpTileDecoder}
 * and acknowledged over UDP (never with {@code FrameAck}); a rectangle that cannot be decoded, or a batch the UI cannot
 * take yet, is reported dropped (the agent resends its tiles) and the session goes on, while a stream larger than the
 * agent's screen or a throwing {@link Listener#onUdpTiles} ends it with {@code Bye("protocol error")}. The paths
 * are chosen by what arrives, not by the {@code TransportState}: UDP rectangles are applied whenever they come,
 * also before the {@code TransportState} that announces them, which only informs (the agent sends no TCP tiles once
 * it decided UDP).
 *
 * <p>All {@link Listener} calls go through the {@code ui} executor; the UI uses {@link #input()} only on its own thread
 * and never waits for the network.
 */
final class ViewerSession {

    private static final String DISCONNECT_THREAD = "mremora-disconnect";
    private static final String PROTOCOL_ERROR = AgentConnection.PROTOCOL_ERROR;
    private static final Logger LOG = System.getLogger(ViewerSession.class.getName());

    /** Events of the session, called through the {@code ui} executor given to {@link #open}. */
    interface Listener {

        /** A decoded frame is ready to be shown (protocol v1). */
        void onFrame(DecodedFrame frame);

        /**
         * A decoded tile frame is ready to be applied to the stream image (protocol v2). The session acks it to the
         * agent once this returns, so the next frame only comes after it; if this throws, the session ends with
         * {@code Bye("protocol error")} instead.
         */
        void onTiles(DecodedTiles tiles);

        /**
         * A decoded batch of changed rectangles of the UDP picture (protocol v3, {@link UdpTileDecoder}) is ready to
         * be applied to the stream image. Returns whether it was applied: {@code false} (e.g. the view does not exist
         * yet) reports its rectangles dropped, so the agent sends their tiles again; if this throws, the session
         * ends with {@code Bye("protocol error")}. By default it is applied like a tile frame.
         */
        default boolean onUdpTiles(DecodedTiles tiles) {
            onTiles(tiles);
            return true;
        }

        /**
         * The session ended; called exactly once. No {@link #onFrame} or {@link #onTiles} follows it only when
         * {@code ui} is a single-threaded FIFO executor such as {@code Platform::runLater}.
         */
        void onClosed(String reason);
    }

    private final AgentConnection connection;
    private final InputTranslator input;
    private CompletableFuture<Void> disconnected;
    /** UI thread only: the last {@link StreamConfig} sent, {@code null} before the first. */
    private StreamConfig lastStreamConfig;

    private ViewerSession(AgentConnection connection) {
        this.connection = connection;
        this.input = new InputTranslator(connection::sendInput);
    }

    /**
     * Connects to an agent, proposing {@link AgentConnection#defaultProtocolVersion()}; see
     * {@link AgentConnection#connect} for the handshake.
     *
     * @param ui runs the listener calls, normally {@code Platform::runLater}
     * @return completes with the established session, or fails with the same exception as
     *     {@link AgentConnection#connect} (then the listener is never called)
     */
    static CompletableFuture<ViewerSession> open(String host, int port, String pairingCode, Executor ui,
                                                 Listener listener) {
        return open(host, port, pairingCode, AgentConnection.defaultProtocolVersion(), ui, listener);
    }

    /** Like {@link #open(String, int, String, Executor, Listener)}, proposing {@code protocolVersion}. */
    static CompletableFuture<ViewerSession> open(String host, int port, String pairingCode, int protocolVersion,
                                                 Executor ui, Listener listener) {
        Objects.requireNonNull(ui, "ui");
        Objects.requireNonNull(listener, "listener");
        // Completed on the connection's thread before any frame is read; the tile sink and the corrupt callback
        // run later, so thenAccept on it acts at once and never waits.
        CompletableFuture<AgentConnection> established = new CompletableFuture<>();
        FrameDecoder frames = new FrameDecoder(ui, listener::onFrame);
        // Tile parts are offered only after the handshake, but the established future may be completed a moment
        // later (on the caller's thread), so the decoder waits for it instead of reading an unknown screen.
        TileDecoder tiles = new TileDecoder(() -> awaitScreen(established), ui,
                decoded -> {
                    try {
                        listener.onTiles(decoded);
                    } catch (Throwable e) {
                        // The frame is a delta: a view that failed to apply it stays damaged, like a corrupt rect.
                        LOG.log(Level.ERROR, "Applying tile frame " + decoded.sequence() + " failed", e);
                        established.thenAccept(c -> c.abort(PROTOCOL_ERROR));
                        return;
                    }
                    established.thenAccept(c -> c.sendFrameAck(decoded.sequence()));
                },
                reason -> established.thenAccept(c -> c.abort(reason)));
        UdpTileDecoder udpTiles = new UdpTileDecoder(() -> awaitScreen(established), ui, listener::onUdpTiles,
                new UdpTileDecoder.Outcomes() {
                    @Override
                    public void applied(long rectId) {
                        established.thenAccept(c -> c.udpRectApplied(rectId));
                    }

                    @Override
                    public void dropped(long rectId) {
                        established.thenAccept(c -> c.udpRectDropped(rectId));
                    }
                },
                reason -> established.thenAccept(c -> c.abort(reason)));
        AgentConnection.Listener connectionListener = new AgentConnection.Listener() {
            @Override
            public void onFrame(FrameUpdate frame) {
                frames.offer(frame);
            }

            @Override
            public void onTiles(TileUpdate part) {
                tiles.offer(part);
            }

            @Override
            public void onUdpRect(CompleteRect rect) {
                udpTiles.offer(rect);
            }

            @Override
            public void onTransportState(TransportState state) {
                LOG.log(Level.INFO, "Picture transport: {0}", state);
            }

            @Override
            public void onClosed(String reason) {
                // Closed before onClosed is queued: no frame delivery runs after it on a FIFO executor.
                frames.close();
                tiles.close();
                udpTiles.close();
                ui.execute(() -> listener.onClosed(reason));
            }
        };
        CompletableFuture<AgentConnection> connecting;
        try {
            connecting = AgentConnection.connect(host, port, pairingCode, protocolVersion, connectionListener);
        } catch (RuntimeException e) {
            frames.close();
            tiles.close();
            udpTiles.close();
            throw e;
        }
        CompletableFuture<ViewerSession> result = new CompletableFuture<>();
        connecting.whenComplete((connection, error) -> {
            if (error != null) {
                frames.close();
                tiles.close();
                udpTiles.close();
                result.completeExceptionally(error);
                return;
            }
            established.complete(connection);
            if (!result.complete(new ViewerSession(connection))) {
                // The caller gave up waiting (e.g. cancelled): nobody owns this session. The listener still gets
                // onClosed("closed by viewer"); the UI never cancels, so this is accepted.
                connection.close();
            }
        });
        return result;
    }

    /** Decoder thread: the agent's screen once the connection is established; an interrupt (close) throws. */
    private static ScreenInfo awaitScreen(CompletableFuture<AgentConnection> established) {
        try {
            return established.get().screenInfo();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Closed while waiting for the handshake", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Connection failed", e);
        }
    }

    /** Geometry of the agent's screen, in physical pixels. */
    ScreenInfo screenInfo() {
        return connection.screenInfo();
    }

    /** The protocol version negotiated with the agent. */
    int protocolVersion() {
        return connection.protocolVersion();
    }

    /**
     * The area showing the remote screen is now {@code width x height} (layout units) on a display with
     * {@code outputScale} physical pixels per unit: asks the agent for the matching stream size when it differs
     * from the one asked for last. UI thread only; debouncing is the caller's job. Nothing is sent on v1.
     */
    void viewChanged(double width, double height, double outputScale) {
        StreamConfig config = StreamConfigs.forView(connection.screenInfo(), width, height, outputScale);
        if (config == null || config.equals(lastStreamConfig)) {
            return;
        }
        lastStreamConfig = config;
        connection.sendStreamConfig(config);
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

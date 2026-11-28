package pl.mtech.mremora.agent;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.FrameAck;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.MouseWheel;
import pl.mtech.mremora.protocol.StreamConfig;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.TransportState;

/**
 * The live remote session on one authenticated channel: streams frames, feeds mouse input to a dedicated thread
 * and ends exactly once, whatever the reason.
 *
 * <p>Protocol v1 streams whole JPEG frames through a one-frame slot. Protocol v2 streams changed tiles, with at
 * most one frame waiting for the viewer's {@code FrameAck} ({@link AckGate}); {@code StreamConfig} resizes the
 * stream from the next frame on. Protocol v3 starts only after the UDP probe phase: over TCP like v2
 * ({@link #announceAndStart}), or over UDP ({@link #announceAndStartUdp}), where a {@link UdpCaptureLoop} hands each
 * capture to a {@link UdpImageChannel} and the UDP sender's gate replaces the {@code FrameAck}.
 *
 * <p>Threads: a capture thread fills the slot (v1) or hands each frame's parts over (v2, v3); the channel's event
 * loop writes frames and owns all mutable state; a single input thread injects mouse events and, last, releases
 * held buttons.
 */
final class AgentSession extends ChannelInboundHandlerAdapter {

    /** Log-only reason: the connection failed or closed, so no {@code Bye} can be sent. */
    private static final String CONNECTION_LOST = "connection lost";
    /** Log-only reason: the viewer said {@code Bye}, which is not answered. */
    private static final String CLOSED_BY_VIEWER = "closed by viewer";

    /** How long a final {@code Bye} may take to flush before the channel is closed anyway. */
    private static final Duration BYE_FLUSH_TIMEOUT = Duration.ofSeconds(2);

    private static final Logger LOG = System.getLogger(AgentSession.class.getName());

    private final Channel channel;
    private final Runnable onEnded;
    private final Runnable onEnd;
    private final BlockingQueue<FrameUpdate> slot = new ArrayBlockingQueue<>(1);
    /** v2 and v3 (tile stream), otherwise {@code null}. */
    private final AckGate gate;
    /** v2 and v3 (tile stream), otherwise {@code null}. */
    private final TileFrameEncoder tileEncoder;
    private final Duration captureInterval;
    /** Event loop; replaced by {@link #announceAndStartUdp} before it starts. */
    private Thread captureThread;
    /** The picture over UDP (v3 decided UDP), otherwise {@code null}. Event loop. */
    private UdpImageChannel udpImage;
    private final InputDispatcher dispatcher;
    private final ExecutorService inputExecutor =
            Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("mremora-input").unstarted(r));

    /** Set by {@link #end} before the final release is queued: input still queued is dropped, not injected. */
    private volatile boolean inputClosed;

    // Event-loop state.
    private boolean frameInFlight;
    private boolean ended;
    private boolean started;

    /**
     * @param protocolVersion the negotiated version, {@code 1..3}
     * @param onEnd           run on the event loop the moment the session ends, before buttons are released
     *                        (e.g. to stop the session's UDP link at once); at most once
     * @param onEnded         run on the event loop once the session has ended and held buttons were released
     */
    AgentSession(Channel channel, ScreenCapturer capturer, InputInjector injector, int maxFps, int protocolVersion,
            Runnable onEnd, Runnable onEnded) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.onEnd = Objects.requireNonNull(onEnd, "onEnd");
        this.onEnded = Objects.requireNonNull(onEnded, "onEnded");
        this.dispatcher = new InputDispatcher(injector);
        Duration interval = Duration.ofMillis(1000 / maxFps);
        this.captureInterval = interval;
        Runnable loop = switch (protocolVersion) {
            case 1 -> {
                this.gate = null;
                this.tileEncoder = null;
                var producer = new FrameProducer(capturer, new JpegFrameEncoder(JpegFrameEncoder.DEFAULT_QUALITY));
                yield new CaptureLoop(producer::next, slot, this::frameReady, this::captureFailed, interval);
            }
            case 2, 3 -> {
                this.gate = new AckGate();
                this.tileEncoder = new TileFrameEncoder(capturer);
                yield new TileCaptureLoop(tileEncoder::next, gate, this::tilesReady, this::captureFailed, interval);
            }
            default -> throw new IllegalArgumentException("Unsupported protocol version " + protocolVersion);
        };
        this.captureThread = captureThread(loop);
    }

    private static Thread captureThread(Runnable loop) {
        return Thread.ofPlatform().name("mremora-capture").daemon(true).unstarted(loop);
    }

    Channel channel() {
        return channel;
    }

    /** Event loop: starts capturing. Call after the handler is in the pipeline. A no-op once ended or started. */
    void start() {
        if (ended || started) {
            return;
        }
        started = true;
        captureThread.start();
    }

    /**
     * Event loop: sends {@code first} (v3: the {@code TransportState}), then starts capturing, so no frame can
     * overtake it. A no-op once the session has ended.
     */
    void announceAndStart(Message first) {
        if (ended) {
            return;
        }
        channel.writeAndFlush(first).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                LOG.log(Level.DEBUG, "Writing {0} failed: {1}", first, String.valueOf(f.cause()));
                end(CONNECTION_LOST, false);
            }
        });
        start();
    }

    /**
     * Event loop, v3 only: the picture goes over UDP through {@code image}. Sends
     * {@code TransportState(UDP, epoch)} (the encoder's epoch, 0 before the first capture), then starts a
     * {@link UdpCaptureLoop}. A no-op (closing {@code image}) once the session has ended or capturing started.
     */
    void announceAndStartUdp(UdpImageChannel image) {
        Objects.requireNonNull(image, "image");
        if (ended || started || tileEncoder == null) {
            image.close();
            return;
        }
        udpImage = image;
        captureThread = captureThread(new UdpCaptureLoop(tileEncoder::nextForUdp, image, this::captureFailed,
                captureInterval));
        announceAndStart(new TransportState(TransportState.Mode.UDP, tileEncoder.epoch()));
    }

    /** Any thread: asks the event loop to end the session with {@code Bye(reason)}. */
    void requestEnd(String reason) {
        onLoop(() -> end(reason));
    }

    /** Event loop: ends the session with {@code Bye(reason)} if the channel is still open. At most once. */
    void end(String reason) {
        end(reason, true);
    }

    /**
     * Any thread: waits until held buttons were released and the input thread stopped.
     *
     * @return {@code false} if it did not stop within {@code timeout}
     */
    boolean awaitInputStopped(Duration timeout) {
        try {
            return inputExecutor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (ended) {
            return;
        }
        switch (msg) {
            case MouseMove m -> inputExecutor.execute(() -> dispatch(m));
            case MouseButton b -> inputExecutor.execute(() -> dispatch(b));
            case MouseWheel w -> inputExecutor.execute(() -> dispatch(w));
            case FrameAck ack when gate != null -> {
                if (!gate.acked(ack.sequence())) {
                    LOG.log(Level.DEBUG, "Ignoring unexpected ack of frame {0}", ack.sequence());
                }
            }
            case StreamConfig config when tileEncoder != null -> tileEncoder.configure(config);
            case Bye bye -> {
                LOG.log(Level.DEBUG, "Viewer said bye: {0}", bye.reason());
                end(CLOSED_BY_VIEWER, false);
            }
            default -> end(AgentServer.PROTOCOL_ERROR);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.DEBUG, "Session error: {0}", cause.toString());
        if (cause instanceof IOException) {
            end(CONNECTION_LOST, false);
        } else {
            end(AgentServer.PROTOCOL_ERROR);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        end(CONNECTION_LOST, false);
        super.channelInactive(ctx);
    }

    /** Event loop: sends the next frame from the slot unless one is still being written. */
    private void pump() {
        if (ended || frameInFlight) {
            return;
        }
        FrameUpdate frame = slot.poll();
        if (frame == null) {
            return;
        }
        frameInFlight = true;
        channel.writeAndFlush(frame).addListener((ChannelFutureListener) f -> {
            frameInFlight = false;
            if (f.isSuccess()) {
                pump();
            } else {
                LOG.log(Level.DEBUG, "Writing a frame failed: {0}", String.valueOf(f.cause()));
                end(CONNECTION_LOST, false);
            }
        });
    }

    /** Event loop: writes the parts of one v2/v3 frame; the gate keeps the next one back until the ack. */
    private void writeTiles(List<TileUpdate> parts) {
        if (ended) {
            return;
        }
        for (int i = 0; i < parts.size(); i++) {
            var future = i == parts.size() - 1 ? channel.writeAndFlush(parts.get(i)) : channel.write(parts.get(i));
            future.addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    LOG.log(Level.DEBUG, "Writing tiles failed: {0}", String.valueOf(f.cause()));
                    end(CONNECTION_LOST, false);
                }
            });
        }
    }

    /** Event loop: the single end path. */
    private void end(String reason, boolean sayBye) {
        if (ended) {
            return;
        }
        ended = true;
        LOG.log(Level.DEBUG, "Ending session: {0}", reason);
        // Interrupt first: the end hook closes the UDP link (and its image channel), and a UDP capture thread that
        // sees the channel closed without an interrupt warns that UDP died mid-session.
        captureThread.interrupt();
        try {
            onEnd.run();
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Session end hook failed", e);
        }
        if (gate != null) {
            gate.close(); // a capture thread parked on the gate must not wait for an ack that never comes
        }
        if (udpImage != null) {
            udpImage.close(); // likewise for one parked on the UDP gate (the link's close closes it too)
        }
        slot.clear();
        inputClosed = true;
        inputExecutor.execute(this::releaseAndReport);
        inputExecutor.shutdown();
        if (sayBye && channel.isActive()) {
            channel.writeAndFlush(new Bye(reason)).addListener(ChannelFutureListener.CLOSE);
            // A viewer that stopped reading holds a frame in flight, so the Bye behind it may never flush.
            var forcedClose = channel.eventLoop().schedule(() -> {
                channel.close();
            }, BYE_FLUSH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            channel.closeFuture().addListener(f -> forcedClose.cancel(false));
        } else {
            channel.close();
        }
    }

    /** Input thread, last task: releases held buttons, then tells the server on the event loop. */
    private void releaseAndReport() {
        try {
            dispatcher.releaseAll();
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Releasing mouse buttons failed", e);
        } finally {
            onLoop(onEnded);
        }
    }

    /** Input thread: injects {@code m} unless the session has ended meanwhile. */
    private void dispatch(Message m) {
        if (inputClosed) {
            return;
        }
        try {
            dispatcher.dispatch(m);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Injecting " + m + " failed", e);
        }
    }

    /** Capture thread. */
    private void frameReady() {
        onLoop(this::pump);
    }

    /** Capture thread: the gate is taken for these parts; the event loop writes them. */
    private void tilesReady(List<TileUpdate> parts) {
        onLoop(() -> writeTiles(parts));
    }

    /** Capture thread. */
    private void captureFailed(RuntimeException e) {
        LOG.log(Level.ERROR, "Capturing the screen failed", e);
        onLoop(() -> end(AgentServer.AGENT_ERROR));
    }

    /** Any thread: runs {@code task} on the channel's event loop; dropped when the loop has already stopped. */
    private void onLoop(Runnable task) {
        try {
            channel.eventLoop().execute(task);
        } catch (RejectedExecutionException e) {
            LOG.log(Level.DEBUG, "Event loop stopped; dropping session task");
        }
    }
}

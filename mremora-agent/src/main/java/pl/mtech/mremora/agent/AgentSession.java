package pl.mtech.mremora.agent;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.MouseWheel;

/**
 * The live remote session on one authenticated channel: streams frames through a one-frame slot, feeds mouse
 * input to a dedicated thread and ends exactly once, whatever the reason.
 *
 * <p>Threads: a capture thread fills the slot; the channel's event loop sends at most one frame at a time and
 * owns all mutable state; a single input thread injects mouse events and, last, releases held buttons.
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
    private final BlockingQueue<FrameUpdate> slot = new ArrayBlockingQueue<>(1);
    private final Thread captureThread;
    private final InputDispatcher dispatcher;
    private final ExecutorService inputExecutor =
            Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("mremora-input").unstarted(r));

    /** Set by {@link #end} before the final release is queued: input still queued is dropped, not injected. */
    private volatile boolean inputClosed;

    // Event-loop state.
    private boolean frameInFlight;
    private boolean ended;

    /**
     * @param onEnded run on the event loop once the session has ended and held buttons were released
     */
    AgentSession(Channel channel, ScreenCapturer capturer, InputInjector injector, int maxFps, Runnable onEnded) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.onEnded = Objects.requireNonNull(onEnded, "onEnded");
        this.dispatcher = new InputDispatcher(injector);
        var producer = new FrameProducer(capturer, new JpegFrameEncoder(JpegFrameEncoder.DEFAULT_QUALITY));
        var loop = new CaptureLoop(producer::next, slot, this::frameReady, this::captureFailed,
                Duration.ofMillis(1000 / maxFps));
        this.captureThread = Thread.ofPlatform().name("mremora-capture").daemon(true).unstarted(loop);
    }

    Channel channel() {
        return channel;
    }

    /** Event loop: starts capturing. Call after the handler is in the pipeline. */
    void start() {
        captureThread.start();
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

    /** Event loop: the single end path. */
    private void end(String reason, boolean sayBye) {
        if (ended) {
            return;
        }
        ended = true;
        LOG.log(Level.DEBUG, "Ending session: {0}", reason);
        captureThread.interrupt();
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

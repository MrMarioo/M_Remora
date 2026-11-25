package pl.mtech.mremora.agent;

import java.util.Objects;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.ScreenInfo;

/**
 * Captures the screen and turns each capture into a sequenced, JPEG-encoded {@link FrameUpdate}.
 *
 * <p>Not thread-safe; call {@link #next()} from the capture loop thread only.
 */
public final class FrameProducer {

    private final ScreenCapturer capturer;
    private final JpegFrameEncoder encoder;
    private long sequence;

    FrameProducer(ScreenCapturer capturer, JpegFrameEncoder encoder) {
        this.capturer = Objects.requireNonNull(capturer, "capturer");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    /** Returns the geometry of the captured screen, in physical pixels. */
    public ScreenInfo screenInfo() {
        return capturer.info();
    }

    /**
     * Captures and encodes the next frame; sequence numbers start at 0 and grow by one per call.
     *
     * @throws java.io.UncheckedIOException if JPEG encoding fails
     */
    public FrameUpdate next() {
        byte[] jpeg = encoder.encode(capturer.capture());
        return new FrameUpdate(sequence++, FrameUpdate.Format.JPEG, jpeg);
    }
}

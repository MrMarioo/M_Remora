package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.ScreenInfo;

class FrameProducerTest {

    private static final ScreenInfo INFO = new ScreenInfo(32, 16, 100);

    private static final ScreenCapturer FAKE = new ScreenCapturer() {
        @Override
        public ScreenInfo info() {
            return INFO;
        }

        @Override
        public BufferedImage capture() {
            return new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB);
        }
    };

    private static FrameProducer producer() {
        return new FrameProducer(FAKE, new JpegFrameEncoder(JpegFrameEncoder.DEFAULT_QUALITY));
    }

    @Test
    void sequenceStartsAtZeroAndIncrements() {
        FrameProducer producer = producer();

        assertThat(producer.next().sequence()).isZero();
        assertThat(producer.next().sequence()).isEqualTo(1);
        assertThat(producer.next().sequence()).isEqualTo(2);
    }

    @Test
    void frameIsJpegOfCapturedImage() throws IOException {
        FrameUpdate frame = producer().next();

        assertThat(frame.format()).isEqualTo(FrameUpdate.Format.JPEG);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(frame.data()));
        assertThat(decoded.getWidth()).isEqualTo(32);
        assertThat(decoded.getHeight()).isEqualTo(16);
    }

    @Test
    void screenInfoComesFromCapturer() {
        assertThat(producer().screenInfo()).isEqualTo(INFO);
    }
}

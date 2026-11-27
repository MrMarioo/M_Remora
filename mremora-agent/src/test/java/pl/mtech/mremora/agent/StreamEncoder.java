package pl.mtech.mremora.agent;

import java.awt.image.BufferedImage;

/** Stateful frame encoder under measurement; a delta-based encoder sends only differences. */
interface StreamEncoder {

    /** @return number of bytes that would be sent for this frame */
    int encodeNext(BufferedImage frame);
}

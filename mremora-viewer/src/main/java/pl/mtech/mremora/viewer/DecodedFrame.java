package pl.mtech.mremora.viewer;

/**
 * A screen frame decoded to pixels, ready to be written into the viewer's image.
 *
 * <p>The {@code argb} array ({@code width * height} pixels, row by row) is handed over from the decoder thread
 * to the UI thread, not copied: nobody may modify it after the frame is created.
 *
 * @param sequence the sequence number of the {@code FrameUpdate} it came from
 * @param encodedBytes size of the encoded (JPEG) payload, for bandwidth statistics
 */
record DecodedFrame(long sequence, int width, int height, int[] argb, int encodedBytes) {}

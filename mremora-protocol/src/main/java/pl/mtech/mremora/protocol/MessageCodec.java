package pl.mtech.mremora.protocol;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Encodes and decodes framed messages.
 *
 * <p>Frame layout, big-endian:
 * <pre>
 * [u32 length][u8 type][u8 flags][payload]
 * </pre>
 * {@code length} counts every byte after the length field itself. Strings are
 * {@code [u16 byte length][UTF-8 bytes]}. Payload layouts are pinned by {@code MessageWireFormatTest}.
 * Flags are reserved and must be 0. Hello is the only extensible message: a decoder ignores
 * bytes after the fields it knows, so newer peers can add fields without breaking negotiation.
 *
 * <p>Stateless and thread-safe. Knows nothing about sockets or Netty.
 */
public final class MessageCodec {

    /** Largest accepted value of the length field. */
    public static final int MAX_FRAME_LENGTH = 16 * 1024 * 1024;

    private static final int LENGTH_FIELD = 4;
    private static final int TYPE_AND_FLAGS = 2;
    private static final int MAX_STRING_BYTES = 0xFFFF;
    private static final int TILE_UPDATE_HEADER = 8 + 2 + 2 + 1 + 2 + 2;
    private static final int TILE_RECT_HEADER = 2 + 2 + 2 + 2 + 1 + 4;
    private static final int TILE_FLAG_LAST_PART = 0x01;

    // ---------------------------------------------------------------- encode

    /**
     * @throws IllegalArgumentException when the message cannot be represented on the wire
     *         (a string over 65535 UTF-8 bytes, a frame over {@link #MAX_FRAME_LENGTH})
     */
    public ByteBuffer encode(Message message) {
        return switch (message) {
            case Hello(int version, Hello.Role role, String code) -> {
                byte[] codeBytes = utf8(code);
                yield frame(MessageType.HELLO, 2 + 1 + 2 + codeBytes.length)
                        .putShort((short) u16(version, "protocol version"))
                        .put(roleCode(role))
                        .putShort((short) codeBytes.length).put(codeBytes)
                        .flip();
            }
            case ScreenInfo(int width, int height, int scale) -> frame(MessageType.SCREEN_INFO, 4 + 4 + 2)
                    .putInt(width).putInt(height).putShort((short) u16(scale, "scale percent"))
                    .flip();
            case FrameUpdate update -> frame(MessageType.FRAME_UPDATE, 8 + 1 + update.dataLength())
                    .putLong(update.sequence()).put(formatCode(update.format())).put(update.data())
                    .flip();
            case TileUpdate update -> encodeTileUpdate(update);
            case FrameAck(long sequence) -> frame(MessageType.FRAME_ACK, 8).putLong(sequence).flip();
            case CursorPosition(int x, int y) -> frame(MessageType.CURSOR_POSITION, 8).putInt(x).putInt(y).flip();
            case StreamConfig(int maxWidth, int maxHeight) -> frame(MessageType.STREAM_CONFIG, 4)
                    .putShort((short) maxWidth).putShort((short) maxHeight)
                    .flip();
            case MouseMove(int x, int y) -> frame(MessageType.MOUSE_MOVE, 8).putInt(x).putInt(y).flip();
            case MouseButton(MouseButton.Button button, boolean pressed) -> frame(MessageType.MOUSE_BUTTON, 2)
                    .put(buttonCode(button)).put((byte) (pressed ? 1 : 0))
                    .flip();
            case MouseWheel(int notches) -> frame(MessageType.MOUSE_WHEEL, 4).putInt(notches).flip();
            case UdpSetup setup -> frame(MessageType.UDP_SETUP, 2 + 4 + UdpSetup.KEY_LENGTH)
                    .putShort((short) setup.udpPort()).putInt(setup.sessionId()).put(setup.key())
                    .flip();
            case TransportState(TransportState.Mode mode, int epoch) -> frame(MessageType.TRANSPORT_STATE, 3)
                    .put(modeCode(mode)).putShort((short) epoch)
                    .flip();
            case Heartbeat() -> frame(MessageType.HEARTBEAT, 0).flip();
            case Bye(String reason) -> {
                byte[] reasonBytes = utf8(reason);
                yield frame(MessageType.BYE, 2 + reasonBytes.length)
                        .putShort((short) reasonBytes.length).put(reasonBytes)
                        .flip();
            }
        };
    }

    private static ByteBuffer encodeTileUpdate(TileUpdate update) {
        long payloadLength = TILE_UPDATE_HEADER;
        for (TileUpdate.Rect rect : update.rects()) {
            payloadLength += TILE_RECT_HEADER + rect.dataLength();
        }
        if (payloadLength + TYPE_AND_FLAGS > MAX_FRAME_LENGTH) { // checked before allocating
            throw new IllegalArgumentException("%s frame of %d bytes exceeds limit %d"
                    .formatted(MessageType.TILE_UPDATE, payloadLength + TYPE_AND_FLAGS, MAX_FRAME_LENGTH));
        }
        ByteBuffer out = frame(MessageType.TILE_UPDATE, (int) payloadLength)
                .putLong(update.sequence())
                .putShort((short) update.streamWidth()).putShort((short) update.streamHeight())
                .put((byte) (update.lastPart() ? TILE_FLAG_LAST_PART : 0))
                .putShort((short) update.latencyMs())
                .putShort((short) update.rects().size());
        for (TileUpdate.Rect rect : update.rects()) {
            out.putShort((short) rect.x()).putShort((short) rect.y())
                    .putShort((short) rect.width()).putShort((short) rect.height())
                    .put(encodingCode(rect.encoding()))
                    .putInt(rect.dataLength()).put(rect.data());
        }
        return out.flip();
    }

    private static ByteBuffer frame(MessageType type, int payloadLength) {
        int length = TYPE_AND_FLAGS + payloadLength;
        if (length > MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("%s frame of %d bytes exceeds limit %d".formatted(type, length, MAX_FRAME_LENGTH));
        }
        return ByteBuffer.allocate(LENGTH_FIELD + length)
                .putInt(length)
                .put((byte) type.code)
                .put((byte) 0);
    }

    private static byte[] utf8(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("String of %d UTF-8 bytes exceeds %d".formatted(bytes.length, MAX_STRING_BYTES));
        }
        return bytes;
    }

    private static int u16(int value, String what) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(what + " out of u16 range: " + value);
        }
        return value;
    }

    private static byte roleCode(Hello.Role role) {
        return switch (role) {
            case AGENT -> 1;
            case VIEWER -> 2;
        };
    }

    private static byte formatCode(FrameUpdate.Format format) {
        return switch (format) {
            case JPEG -> 1;
        };
    }

    private static byte encodingCode(TileUpdate.Encoding encoding) {
        return switch (encoding) {
            case SOLID -> 1;
            case LOSSLESS -> 2;
            case JPEG -> 3;
            case COPY -> 4;
        };
    }

    private static byte modeCode(TransportState.Mode mode) {
        return switch (mode) {
            case TCP -> 1;
            case UDP -> 2;
        };
    }

    private static byte buttonCode(MouseButton.Button button) {
        return switch (button) {
            case LEFT -> 1;
            case MIDDLE -> 2;
            case RIGHT -> 3;
        };
    }

    // ---------------------------------------------------------------- decode

    /**
     * Decodes one frame from the current position of {@code in}. The buffer must be big-endian
     * (the {@link ByteBuffer} default).
     *
     * @return the message, or empty when the frame is not complete yet; in that case
     *         {@code in} is left untouched so the caller can append more bytes and retry
     * @throws ProtocolException when the bytes can never form a valid frame
     */
    public Optional<Message> decode(ByteBuffer in) {
        return decode(in, MAX_FRAME_LENGTH);
    }

    /**
     * Like {@link #decode(ByteBuffer)} but rejects frames whose length field exceeds
     * {@code maxFrameLength}, as soon as the length field is readable.
     *
     * @param maxFrameLength largest accepted length field, in {@code [2, MAX_FRAME_LENGTH]}
     * @throws IllegalArgumentException when {@code maxFrameLength} is outside that range
     * @throws ProtocolException when the bytes can never form a valid frame within the limit
     */
    public Optional<Message> decode(ByteBuffer in, int maxFrameLength) {
        if (maxFrameLength < TYPE_AND_FLAGS || maxFrameLength > MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException(
                    "maxFrameLength must be %d..%d, was %d".formatted(TYPE_AND_FLAGS, MAX_FRAME_LENGTH, maxFrameLength));
        }
        if (in.remaining() < LENGTH_FIELD) {
            return Optional.empty();
        }
        int length = in.getInt(in.position()); // peek, don't consume
        if (length < TYPE_AND_FLAGS || length > maxFrameLength) {
            throw new ProtocolException("Invalid frame length " + length);
        }
        if (in.remaining() < LENGTH_FIELD + length) {
            return Optional.empty();
        }

        in.getInt();
        MessageType type = MessageType.fromCode(in.get() & 0xFF);
        int flags = in.get() & 0xFF;
        if (flags != 0) {
            // reserved: a future flag (e.g. compression) may change how the payload is read
            throw new ProtocolException("Unsupported frame flags 0x%02x".formatted(flags));
        }

        ByteBuffer payload = in.slice(in.position(), length - TYPE_AND_FLAGS);
        in.position(in.position() + payload.limit());
        return Optional.of(decodePayload(type, payload));
    }

    private static Message decodePayload(MessageType type, ByteBuffer p) {
        Message message;
        try {
            message = switch (type) {
                case HELLO -> new Hello(p.getShort() & 0xFFFF, role(p.get()), string(p));
                case SCREEN_INFO -> new ScreenInfo(p.getInt(), p.getInt(), p.getShort() & 0xFFFF);
                case FRAME_UPDATE -> {
                    long sequence = p.getLong();
                    FrameUpdate.Format format = format(p.get());
                    byte[] data = new byte[p.remaining()];
                    p.get(data);
                    yield new FrameUpdate(sequence, format, data);
                }
                case TILE_UPDATE -> tileUpdate(p);
                case FRAME_ACK -> new FrameAck(p.getLong());
                case CURSOR_POSITION -> new CursorPosition(p.getInt(), p.getInt());
                case STREAM_CONFIG -> new StreamConfig(p.getShort() & 0xFFFF, p.getShort() & 0xFFFF);
                case MOUSE_MOVE -> new MouseMove(p.getInt(), p.getInt());
                case MOUSE_BUTTON -> new MouseButton(button(p.get()), pressed(p.get()));
                case MOUSE_WHEEL -> new MouseWheel(p.getInt());
                case UDP_SETUP -> udpSetup(p);
                case TRANSPORT_STATE -> new TransportState(mode(p.get()), p.getShort() & 0xFFFF);
                case HEARTBEAT -> new Heartbeat();
                case BYE -> new Bye(string(p));
            };
        } catch (BufferUnderflowException e) {
            throw new ProtocolException(type + " payload is too short");
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(type + " has invalid content: " + e.getMessage());
        }
        // Hello may grow in later versions and must stay readable, or version negotiation breaks.
        // Every other message is sent only after the version is agreed, so extra bytes are an error.
        if (p.hasRemaining() && type != MessageType.HELLO) {
            throw new ProtocolException(type + " payload has " + p.remaining() + " unexpected trailing bytes");
        }
        return message;
    }

    private static UdpSetup udpSetup(ByteBuffer p) {
        int port = p.getShort() & 0xFFFF;
        int sessionId = p.getInt();
        byte[] key = new byte[UdpSetup.KEY_LENGTH];
        p.get(key);
        return new UdpSetup(port, sessionId, key);
    }

    private static TileUpdate tileUpdate(ByteBuffer p) {
        long sequence = p.getLong();
        int streamWidth = p.getShort() & 0xFFFF;
        int streamHeight = p.getShort() & 0xFFFF;
        int tileFlags = p.get() & 0xFF;
        if ((tileFlags & ~TILE_FLAG_LAST_PART) != 0) {
            throw new ProtocolException("Unsupported tile flags 0x%02x".formatted(tileFlags));
        }
        int latencyMs = p.getShort() & 0xFFFF;
        int count = p.getShort() & 0xFFFF;
        // do not trust count for sizing: each rect needs at least TILE_RECT_HEADER bytes
        List<TileUpdate.Rect> rects = new ArrayList<>(Math.min(count, p.remaining() / TILE_RECT_HEADER));
        for (int i = 0; i < count; i++) {
            int x = p.getShort() & 0xFFFF;
            int y = p.getShort() & 0xFFFF;
            int width = p.getShort() & 0xFFFF;
            int height = p.getShort() & 0xFFFF;
            TileUpdate.Encoding encoding = encoding(p.get());
            long length = p.getInt() & 0xFFFFFFFFL;
            if (length > p.remaining()) { // before allocating: u32 length is attacker-controlled
                throw new BufferUnderflowException();
            }
            byte[] data = new byte[(int) length];
            p.get(data);
            rects.add(new TileUpdate.Rect(x, y, width, height, encoding, data));
        }
        return new TileUpdate(sequence, streamWidth, streamHeight, (tileFlags & TILE_FLAG_LAST_PART) != 0, latencyMs, rects);
    }

    private static TileUpdate.Encoding encoding(byte code) {
        return switch (code) {
            case 1 -> TileUpdate.Encoding.SOLID;
            case 2 -> TileUpdate.Encoding.LOSSLESS;
            case 3 -> TileUpdate.Encoding.JPEG;
            case 4 -> TileUpdate.Encoding.COPY;
            default -> throw new ProtocolException("Unknown tile encoding " + code);
        };
    }

    private static String string(ByteBuffer p) {
        int length = p.getShort() & 0xFFFF;
        if (p.remaining() < length) {
            throw new BufferUnderflowException();
        }
        ByteBuffer bytes = p.slice(p.position(), length);
        p.position(p.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("String is not valid UTF-8");
        }
    }

    private static Hello.Role role(byte code) {
        return switch (code) {
            case 1 -> Hello.Role.AGENT;
            case 2 -> Hello.Role.VIEWER;
            default -> throw new ProtocolException("Unknown role " + code);
        };
    }

    private static FrameUpdate.Format format(byte code) {
        return switch (code) {
            case 1 -> FrameUpdate.Format.JPEG;
            default -> throw new ProtocolException("Unknown frame format " + code);
        };
    }

    private static TransportState.Mode mode(byte code) {
        return switch (code) {
            case 1 -> TransportState.Mode.TCP;
            case 2 -> TransportState.Mode.UDP;
            default -> throw new ProtocolException("Unknown transport mode " + code);
        };
    }

    private static MouseButton.Button button(byte code) {
        return switch (code) {
            case 1 -> MouseButton.Button.LEFT;
            case 2 -> MouseButton.Button.MIDDLE;
            case 3 -> MouseButton.Button.RIGHT;
            default -> throw new ProtocolException("Unknown mouse button " + code);
        };
    }

    private static boolean pressed(byte code) {
        return switch (code) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new ProtocolException("Invalid pressed flag " + code);
        };
    }
}

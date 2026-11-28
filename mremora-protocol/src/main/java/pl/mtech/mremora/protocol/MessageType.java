package pl.mtech.mremora.protocol;

/**
 * Wire codes of message types. Codes are part of the protocol: never renumber, only add.
 */
enum MessageType {
    HELLO(0x01),
    SCREEN_INFO(0x02),
    FRAME_UPDATE(0x10),
    TILE_UPDATE(0x11),
    FRAME_ACK(0x12),
    // 0x13 is reserved for a future message (planned: cursor shape, Stage 5)
    CURSOR_POSITION(0x14),
    MOUSE_MOVE(0x20),
    MOUSE_BUTTON(0x21),
    MOUSE_WHEEL(0x22),
    STREAM_CONFIG(0x23),
    HEARTBEAT(0x30),
    BYE(0x31),
    UDP_SETUP(0x32),
    TRANSPORT_STATE(0x33);

    final int code;

    MessageType(int code) {
        this.code = code;
    }

    static MessageType fromCode(int code) {
        for (MessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ProtocolException("Unknown message type 0x%02x".formatted(code));
    }
}

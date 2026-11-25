package pl.mtech.mremora.protocol;

/**
 * Wire codes of message types. Codes are part of the protocol: never renumber, only add.
 */
enum MessageType {
    HELLO(0x01),
    SCREEN_INFO(0x02),
    FRAME_UPDATE(0x10),
    MOUSE_MOVE(0x20),
    MOUSE_BUTTON(0x21),
    MOUSE_WHEEL(0x22),
    HEARTBEAT(0x30),
    BYE(0x31);

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

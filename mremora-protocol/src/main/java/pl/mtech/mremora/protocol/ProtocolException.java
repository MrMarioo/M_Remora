package pl.mtech.mremora.protocol;

/**
 * The byte stream violates the protocol. The connection that produced it should be closed.
 */
public final class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }
}

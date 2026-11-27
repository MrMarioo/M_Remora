package pl.mtech.mremora.agent;

/**
 * At most one v2 frame in flight: {@link #sent} takes the gate for a sequence, and only the viewer's
 * {@code FrameAck} of exactly that sequence frees it. A stale or duplicate ack is refused. {@link #close()} frees
 * every waiter for good, so a capture thread never stays parked after the session ended. Thread-safe.
 */
final class AckGate {

    private boolean busy;
    private long awaited;
    private boolean closed;

    /** Blocks while a frame waits for its ack; returns at once after {@link #close()}. */
    synchronized void awaitFree() throws InterruptedException {
        while (busy && !closed) {
            wait();
        }
    }

    /** Takes the gate until {@code seq} is acked. */
    synchronized void sent(long seq) {
        busy = true;
        awaited = seq;
    }

    /** @return whether this ack freed the gate: only for the sequence that is awaited right now */
    synchronized boolean acked(long seq) {
        if (!busy || seq != awaited) {
            return false;
        }
        busy = false;
        notifyAll();
        return true;
    }

    /** @return whether {@link #close()} was called: the session is over */
    synchronized boolean isClosed() {
        return closed;
    }

    /** Wakes every waiter; later {@link #awaitFree()} calls never block. */
    synchronized void close() {
        closed = true;
        notifyAll();
    }
}

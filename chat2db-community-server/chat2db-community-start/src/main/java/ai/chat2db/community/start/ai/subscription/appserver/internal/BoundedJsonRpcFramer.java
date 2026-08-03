package ai.chat2db.community.start.ai.subscription.appserver.internal;

import ai.chat2db.community.start.ai.subscription.appserver.AppServerDisabledReason;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerException;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Newline-delimited JSON-RPC framing with a hard per-message UTF-8 size bound.
 * Official app-server stdio transport is JSONL without requiring a jsonrpc header.
 * Writes are synchronized so concurrent requests cannot interleave frames.
 */
public final class BoundedJsonRpcFramer implements AutoCloseable {

    private final InputStream in;
    private final OutputStream out;
    private final int maxMessageBytes;
    private final Object writeLock = new Object();
    private final Object readLock = new Object();
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);

    public BoundedJsonRpcFramer(InputStream in, OutputStream out) {
        this(in, out, AppServerProtocol.DEFAULT_MAX_MESSAGE_BYTES);
    }

    public BoundedJsonRpcFramer(InputStream in, OutputStream out, int maxMessageBytes) {
        if (maxMessageBytes < 64) {
            throw new IllegalArgumentException("maxMessageBytes too small");
        }
        this.in = in;
        this.out = out;
        this.maxMessageBytes = maxMessageBytes;
    }

    /**
     * Reads one newline-delimited frame. Bounds by UTF-8 bytes while reading so an
     * oversized frame never materializes as an unbounded Java String first.
     */
    public String readMessage() throws IOException {
        synchronized (readLock) {
            ByteBuffer raw = ByteBuffer.allocate(maxMessageBytes + 1);
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    break;
                }
                if (b == '\r') {
                    // Tolerate CRLF by dropping CR; next loop iteration handles LF or continues.
                    continue;
                }
                if (!raw.hasRemaining()) {
                    // Drain until newline or EOF so the next frame can be framed cleanly, then fail.
                    drainUntilNewline();
                    throw new AppServerException(
                            AppServerDisabledReason.OVERSIZED_MESSAGE,
                            "app-server frame exceeds maxMessageBytes=" + maxMessageBytes);
                }
                raw.put((byte) b);
            }
            if (b == -1 && raw.position() == 0) {
                return null;
            }
            raw.flip();
            if (!raw.hasRemaining() && b == -1) {
                return null;
            }
            try {
                CharBuffer chars = decoder.decode(raw);
                return chars.toString();
            } catch (Exception ex) {
                throw new AppServerException(
                        AppServerDisabledReason.MALFORMED_MESSAGE,
                        "app-server frame is not valid UTF-8",
                        ex);
            } finally {
                decoder.reset();
            }
        }
    }

    public void writeMessage(String jsonLine) throws IOException {
        if (jsonLine == null) {
            throw new AppServerException(
                    AppServerDisabledReason.MALFORMED_MESSAGE,
                    "refusing to write null JSON-RPC frame");
        }
        if (jsonLine.indexOf('\n') >= 0 || jsonLine.indexOf('\r') >= 0) {
            throw new AppServerException(
                    AppServerDisabledReason.MALFORMED_MESSAGE,
                    "JSON-RPC frame must not contain raw newlines");
        }
        byte[] payload = jsonLine.getBytes(StandardCharsets.UTF_8);
        if (payload.length > maxMessageBytes) {
            throw new AppServerException(
                    AppServerDisabledReason.OVERSIZED_MESSAGE,
                    "outgoing app-server frame exceeds maxMessageBytes=" + maxMessageBytes);
        }
        synchronized (writeLock) {
            out.write(payload);
            out.write('\n');
            out.flush();
        }
    }

    private void drainUntilNewline() throws IOException {
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            out.close();
        } finally {
            in.close();
        }
    }
}

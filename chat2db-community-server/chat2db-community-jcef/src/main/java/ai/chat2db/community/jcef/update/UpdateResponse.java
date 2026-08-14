package ai.chat2db.community.jcef.update;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Validated, closeable, streaming response for an update payload.
 * Implementations must not buffer the full payload body in memory.
 */
public interface UpdateResponse extends Closeable {

    InputStream openStream() throws IOException;

    long contentLengthOrMinusOne();

    int statusCode();

    /**
     * Returns an HTTP response header when the transport exposes one, otherwise
     * {@code null}. Payload resumption uses Content-Range as part of its
     * integrity contract.
     */
    String header(String name);
}

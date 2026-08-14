package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

final class HttpUpdateResponse implements UpdateResponse {

    private final HttpURLConnection connection;
    private final int statusCode;
    private InputStream stream;

    HttpUpdateResponse(HttpURLConnection connection, int statusCode) {
        this.connection = connection;
        this.statusCode = statusCode;
    }

    @Override
    public InputStream openStream() throws IOException {
        if (stream == null) {
            stream = connection.getInputStream();
        }
        return stream;
    }

    @Override
    public long contentLengthOrMinusOne() {
        return connection.getContentLengthLong();
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public String header(String name) {
        return connection.getHeaderField(name);
    }

    @Override
    public void close() {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) {
        } finally {
            connection.disconnect();
        }
    }
}

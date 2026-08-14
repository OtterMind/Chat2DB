package ai.chat2db.community.jcef.update;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

class MockHttpURLConnection extends HttpURLConnection {

    private final int responseCode;
    private final Map<String, String> headers = new HashMap<>();
    private byte[] body = new byte[0];

    MockHttpURLConnection(URI uri, int responseCode) {
        super(toUrl(uri));
        this.responseCode = responseCode;
    }

    private static URL toUrl(URI uri) {
        try {
            return uri.toURL();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    MockHttpURLConnection withHeader(String name, String value) {
        headers.put(name.toLowerCase(Locale.ROOT), value);
        return this;
    }

    MockHttpURLConnection withBody(byte[] body) {
        this.body = body;
        return this;
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public String getHeaderField(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(body);
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public boolean usingProxy() {
        return false;
    }

    @Override
    public void connect() throws IOException {
        connected = true;
    }
}

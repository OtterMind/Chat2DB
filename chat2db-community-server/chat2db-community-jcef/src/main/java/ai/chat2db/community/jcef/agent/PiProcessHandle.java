package ai.chat2db.community.jcef.agent;

import java.io.InputStream;
import java.io.OutputStream;

public record PiProcessHandle(String sessionId, Process process) implements AutoCloseable {

    public InputStream stdout() {
        return process.getInputStream();
    }

    public InputStream stderr() {
        return process.getErrorStream();
    }

    public OutputStream stdin() {
        return process.getOutputStream();
    }

    @Override
    public void close() {
        process.destroy();
    }
}

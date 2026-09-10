package ai.chat2db.community.web.api.model.response.agent;

import java.io.IOException;
import java.io.InputStream;

public record AgentModelGatewayResponse(int statusCode, String contentType, InputStream body) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        body.close();
    }
}

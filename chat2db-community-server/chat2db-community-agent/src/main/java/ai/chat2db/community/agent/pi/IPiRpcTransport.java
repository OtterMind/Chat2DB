package ai.chat2db.community.agent.pi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;

public interface IPiRpcTransport extends AutoCloseable {

    CompletableFuture<JsonNode> request(String command, JsonNode payload);

    CompletableFuture<Void> termination();

    @Override
    void close();
}

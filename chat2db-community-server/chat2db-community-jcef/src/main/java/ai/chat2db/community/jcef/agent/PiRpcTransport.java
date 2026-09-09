package ai.chat2db.community.jcef.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

public interface PiRpcTransport extends AutoCloseable {

    CompletableFuture<JsonNode> request(String command, JsonNode payload);

    CompletableFuture<Void> termination();

    @Override
    void close();
}

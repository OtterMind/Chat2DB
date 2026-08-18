package ai.chat2db.community.runtime.codex;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface CodexServerRequestHandler {
    JsonNode handle(String method, JsonNode params);
}

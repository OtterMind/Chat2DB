package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.domain.api.model.ai.AiRuntimeModel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the URL a configured OpenAI-compatible model actually posts to, so a
 * base URL that already carries a version segment (Zhipu GLM's "/v4") never
 * receives OpenAiApi's default "/v1/chat/completions" on top of it.
 */
class AiModelFactoryBaseUrlTest {

    private static final String CHAT_COMPLETION_RESPONSE = """
            {
              "id": "chatcmpl-test",
              "object": "chat.completion",
              "created": 1,
              "model": "glm-test",
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "pong"},
                  "finish_reason": "stop"
                }
              ],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }""";

    @Test
    void openAiCompatibleRequestKeepsTheProviderVersionSegment() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> receivedPath = new AtomicReference<>();
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getRawPath());
            byte[] body = CHAT_COMPLETION_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/paas/v4";

            AiModelFactory.AiChatClient client = new AiModelFactory(new GenericApplicationContext())
                    .create(runtimeModel(baseUrl), AiModelFactory.RequestMode.SYNCHRONOUS);
            String answer = client.getChatClient().prompt().user("ping").call().content();

            assertEquals("pong", answer);
            assertEquals("/api/paas/v4/chat/completions", receivedPath.get());
        } finally {
            server.stop(0);
        }
    }

    private AiRuntimeModel runtimeModel(String baseUrl) {
        AiRuntimeModel runtimeModel = new AiRuntimeModel();
        runtimeModel.setProvider("OPENAI");
        runtimeModel.setModel("glm-test");
        runtimeModel.setApiKey("sk-test");
        runtimeModel.setBaseUrl(baseUrl);
        return runtimeModel;
    }
}

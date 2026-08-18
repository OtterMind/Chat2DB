package ai.chat2db.community.runtime.dsh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DshBridgeJsonRpcClientTest {

    @Test
    void pausesRequestTimeoutWhileApprovalIsPending() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PipedInputStream clientInput = new PipedInputStream();
        PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        PipedInputStream serverInput = new PipedInputStream();
        PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        AtomicBoolean approvalWaiting = new AtomicBoolean(true);

        Thread server = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverInput, StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(serverOutput, StandardCharsets.UTF_8))) {
                JsonNode request = mapper.readTree(reader.readLine());
                Thread.sleep(350L);
                approvalWaiting.set(false);
                ObjectNode response = mapper.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", request.get("id"));
                response.set("result", mapper.createObjectNode().put("value", "continued"));
                writer.write(mapper.writeValueAsString(response));
                writer.newLine();
                writer.flush();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        server.start();

        try (DshBridgeJsonRpcClient client = new DshBridgeJsonRpcClient(mapper, clientInput, clientOutput,
                ignored -> { }, ignored -> { })) {
            JsonNode result = client.request("turn/start", mapper.createObjectNode(),
                    Duration.ofMillis(100), approvalWaiting::get);
            assertEquals("continued", result.path("value").asText());
        }
        server.join(2_000L);
    }
}

package ai.chat2db.community.jcef.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiRpcClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipedInputStream runtimeOutput = new PipedInputStream();
    private final PipedOutputStream runtimeWriter;
    private final ByteArrayOutputStream runtimeInput = new ByteArrayOutputStream();
    private final List<JsonNode> events = new CopyOnWriteArrayList<>();
    private PiRpcClient client;

    PiRpcClientTest() throws Exception {
        runtimeWriter = new PipedOutputStream(runtimeOutput);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        runtimeWriter.close();
    }

    @Test
    void correlatesResponsesAndSeparatesEvents() throws Exception {
        client = new PiRpcClient(runtimeOutput, runtimeInput, events::add);

        var response = client.request("prompt", objectMapper.readTree("{\"text\":\"hello\"}"));
        JsonNode request = awaitWrittenRequest();
        writeLine("{\"type\":\"agent_start\",\"runId\":\"run-one\"}\r\n");
        writeLine("{\"id\":\"" + request.get("id").asText()
                + "\",\"type\":\"response\",\"command\":\"prompt\",\"success\":true}\n");

        assertTrue(response.get(1, TimeUnit.SECONDS).isObject());
        awaitEvent();
        assertEquals("agent_start", events.get(0).get("type").asText());
        assertEquals("prompt", request.get("type").asText());
    }

    @Test
    void failsPendingRequestsOnInvalidJson() throws Exception {
        client = new PiRpcClient(runtimeOutput, runtimeInput, events::add);
        var response = client.request("prompt", objectMapper.createObjectNode());
        awaitWrittenRequest();

        writeLine("not-json\n");

        ExecutionException error = assertThrows(
                ExecutionException.class, () -> response.get(1, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof PiRpcException);
        assertThrows(ExecutionException.class, () -> client.termination().get(1, TimeUnit.SECONDS));
    }

    @Test
    void rejectsUnknownResponseIds() throws Exception {
        client = new PiRpcClient(runtimeOutput, runtimeInput, events::add);

        writeLine("{\"id\":\"unknown\",\"type\":\"response\",\"success\":true}\n");

        ExecutionException error = assertThrows(
                ExecutionException.class, () -> client.termination().get(1, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("unknown request id"));
    }

    @Test
    void requiresLfAndEnforcesMaximumFrameSize() throws Exception {
        client = new PiRpcClient(
                runtimeOutput, runtimeInput, events::add, objectMapper, 8,
                () -> "request", java.util.concurrent.Executors.newSingleThreadExecutor());

        writeLine("123456789");

        ExecutionException error = assertThrows(
                ExecutionException.class, () -> client.termination().get(1, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("size limit"));
    }

    @Test
    void failsOversizedRequestsWithoutWritingThem() throws Exception {
        client = new PiRpcClient(
                runtimeOutput, runtimeInput, events::add, objectMapper, 16,
                () -> "request", java.util.concurrent.Executors.newSingleThreadExecutor());

        var response = client.request("prompt", objectMapper.createObjectNode().put("text", "too large"));

        assertThrows(ExecutionException.class, () -> response.get(1, TimeUnit.SECONDS));
        assertEquals(0, runtimeInput.size());
    }

    @Test
    void routesExtensionUiRequestsWithIdsAsEvents() throws Exception {
        client = new PiRpcClient(runtimeOutput, runtimeInput, events::add);

        writeLine("{\"id\":\"dialog\",\"type\":\"extension_ui_request\",\"method\":\"confirm\"}\n");

        awaitEvent();
        assertEquals("dialog", events.get(0).get("id").asText());
    }

    private JsonNode awaitWrittenRequest() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (runtimeInput.size() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        String line = runtimeInput.toString(StandardCharsets.UTF_8).strip();
        return objectMapper.readTree(line);
    }

    private void awaitEvent() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (events.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, events.size());
    }

    private void writeLine(String value) throws Exception {
        runtimeWriter.write(value.getBytes(StandardCharsets.UTF_8));
        runtimeWriter.flush();
    }
}

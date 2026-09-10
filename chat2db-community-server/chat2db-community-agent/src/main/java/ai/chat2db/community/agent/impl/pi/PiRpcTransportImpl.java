package ai.chat2db.community.agent.impl.pi;

import ai.chat2db.community.agent.exception.pi.PiRpcException;
import ai.chat2db.community.agent.pi.IPiRpcTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PiRpcTransportImpl implements IPiRpcTransport {

    public static final int DEFAULT_MAXIMUM_FRAME_BYTES = 8 * 1024 * 1024;

    private final InputStream stdout;
    private final OutputStream stdin;
    private final ObjectMapper objectMapper;
    private final int maximumFrameBytes;
    private final Consumer<JsonNode> eventConsumer;
    private final Supplier<String> idGenerator;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ExecutorService readerExecutor;
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PiRpcTransportImpl(InputStream stdout, OutputStream stdin, Consumer<JsonNode> eventConsumer) {
        this(stdout, stdin, eventConsumer, new ObjectMapper(), DEFAULT_MAXIMUM_FRAME_BYTES,
                () -> UUID.randomUUID().toString(), Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "chat2db-pi-rpc-reader");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    PiRpcTransportImpl(
            InputStream stdout,
            OutputStream stdin,
            Consumer<JsonNode> eventConsumer,
            ObjectMapper objectMapper,
            int maximumFrameBytes,
            Supplier<String> idGenerator,
            ExecutorService readerExecutor) {
        if (maximumFrameBytes < 1) {
            throw new IllegalArgumentException("maximumFrameBytes must be greater than zero");
        }
        this.stdout = stdout;
        this.stdin = stdin;
        this.eventConsumer = eventConsumer;
        this.objectMapper = objectMapper;
        this.maximumFrameBytes = maximumFrameBytes;
        this.idGenerator = idGenerator;
        this.readerExecutor = readerExecutor;
        readerExecutor.execute(this::readLoop);
    }

    public CompletableFuture<JsonNode> request(String command, JsonNode payload) {
        requireText(command, "command");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new PiRpcException("Pi RPC client is closed"));
        }
        String id = requireId(idGenerator.get());
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        if (pending.putIfAbsent(id, response) != null) {
            return CompletableFuture.failedFuture(new PiRpcException("Pi RPC request id was reused"));
        }
        ObjectNode request = objectMapper.createObjectNode();
        request.put("id", id);
        request.put("type", command);
        if (payload != null) {
            if (!payload.isObject() || payload.has("id") || payload.has("type")) {
                pending.remove(id, response);
                return CompletableFuture.failedFuture(
                        new PiRpcException("Pi RPC payload must be an object without id or type"));
            }
            request.setAll((ObjectNode) payload);
        }
        try {
            writeFrame(request);
        } catch (IOException | RuntimeException error) {
            pending.remove(id, response);
            response.completeExceptionally(new PiRpcException("Cannot write Pi RPC request", error));
        }
        return response;
    }

    public CompletableFuture<Void> termination() {
        return termination;
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new PiRpcException("Pi RPC stdout closed unexpectedly");
                }
                route(objectMapper.readTree(frame));
            }
        } catch (IOException | RuntimeException error) {
            fail(error instanceof PiRpcException ? error : new PiRpcException("Invalid Pi RPC stream", error));
        }
    }

    private byte[] readFrame() throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int value;
        while ((value = stdout.read()) >= 0) {
            if (value == '\n') {
                byte[] bytes = frame.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                if (length == 0) {
                    throw new PiRpcException("Pi RPC emitted an empty frame");
                }
                return length == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, length);
            }
            if (frame.size() >= maximumFrameBytes) {
                throw new PiRpcException("Pi RPC frame exceeds the size limit");
            }
            frame.write(value);
        }
        if (frame.size() != 0) {
            throw new PiRpcException("Pi RPC stdout ended without LF framing");
        }
        return null;
    }

    private void route(JsonNode message) {
        if (!message.isObject()) {
            throw new PiRpcException("Pi RPC frame must be a JSON object");
        }
        JsonNode typeNode = message.get("type");
        if (typeNode == null || !typeNode.isTextual() || typeNode.asText().isBlank()) {
            throw new PiRpcException("Pi RPC frame has no valid type");
        }
        if (!"response".equals(typeNode.asText())) {
            eventConsumer.accept(message);
            return;
        }
        JsonNode idNode = message.get("id");
        if (idNode == null || !idNode.isTextual() || idNode.asText().isBlank()) {
            throw new PiRpcException("Pi RPC response id is invalid");
        }
        CompletableFuture<JsonNode> response = pending.remove(idNode.asText());
        if (response == null) {
            throw new PiRpcException("Pi RPC response has an unknown request id");
        }
        if (!message.path("success").asBoolean(false)) {
            response.completeExceptionally(new PiRpcException(
                    "Pi RPC command failed: " + message.path("error").asText("unknown error")));
            return;
        }
        response.complete(message.has("data") ? message.get("data") : objectMapper.createObjectNode());
    }

    private synchronized void writeFrame(JsonNode request) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(request);
        if (bytes.length > maximumFrameBytes) {
            throw new PiRpcException("Pi RPC request exceeds the size limit");
        }
        stdin.write(bytes);
        stdin.write('\n');
        stdin.flush();
    }

    private void fail(Throwable error) {
        if (closed.compareAndSet(false, true)) {
            pending.values().forEach(future -> future.completeExceptionally(error));
            pending.clear();
            termination.completeExceptionally(error);
            readerExecutor.shutdownNow();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            PiRpcException error = new PiRpcException("Pi RPC client was closed");
            pending.values().forEach(future -> future.completeExceptionally(error));
            pending.clear();
            readerExecutor.shutdownNow();
            termination.complete(null);
            try {
                stdout.close();
                stdin.close();
            } catch (IOException ignored) {
                // Closing is best effort after all callers have been notified.
            }
        }
    }

    private String requireId(String id) {
        requireText(id, "id");
        return id;
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

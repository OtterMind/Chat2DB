package ai.chat2db.community.runtime.dsh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class DshBridgeJsonRpcClient implements Closeable {

    private static final int MAX_MESSAGE_CHARS = 4 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Consumer<JsonNode> notifications;
    private final Consumer<Throwable> failures;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1L);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeMonitor = new Object();

    DshBridgeJsonRpcClient(ObjectMapper mapper, InputStream input, OutputStream output,
                           Consumer<JsonNode> notifications, Consumer<Throwable> failures) {
        this.mapper = mapper;
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.notifications = notifications;
        this.failures = failures;
        Thread thread = new Thread(this::readLoop, "chat2db-dsh-bridge-reader");
        thread.setDaemon(true);
        thread.start();
    }

    JsonNode request(String method, JsonNode params, Duration timeout) {
        if (closed.get()) {
            throw new IllegalStateException("DSH Runtime Bridge connection is closed");
        }
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        pending.put(id, response);
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? mapper.createObjectNode() : params);
        try {
            write(message);
            return response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.remove(id);
            throw new IllegalStateException("DSH Runtime Bridge request timed out: " + method, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("DSH Runtime Bridge request failed: " + method, cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DSH Runtime Bridge request interrupted: " + method, exception);
        } catch (RuntimeException exception) {
            pending.remove(id);
            throw exception;
        }
    }

    private void readLoop() {
        try {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (line.length() > MAX_MESSAGE_CHARS) {
                    throw new IOException("DSH Runtime Bridge message exceeds size limit");
                }
                dispatch(mapper.readTree(line));
            }
            if (!closed.get()) failConnection(new IOException("DSH Runtime Bridge stream closed"));
        } catch (Throwable failure) {
            if (!closed.get()) failConnection(failure);
        }
    }

    private void dispatch(JsonNode message) {
        JsonNode id = message.get("id");
        if (id != null && (message.has("result") || message.has("error"))) {
            CompletableFuture<JsonNode> future = pending.remove(id.asLong());
            if (future == null) return;
            if (message.has("error")) {
                int code = message.path("error").path("code").asInt();
                String text = message.path("error").path("message").asText("DSH Runtime Bridge error");
                future.completeExceptionally(new DshBridgeException(code, text));
            } else {
                future.complete(message.get("result"));
            }
        } else if (message.hasNonNull("method")) {
            notifications.accept(message);
        }
    }

    private void write(JsonNode message) {
        synchronized (writeMonitor) {
            try {
                writer.write(mapper.writeValueAsString(message));
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write DSH Runtime Bridge message", exception);
            }
        }
    }

    private void failConnection(Throwable failure) {
        pending.values().forEach(future -> future.completeExceptionally(failure));
        pending.clear();
        failures.accept(failure);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            writer.close();
        } catch (IOException ignored) {
            // The owning adapter terminates the bridge process immediately afterwards.
        }
        pending.values().forEach(future -> future.completeExceptionally(
                new IllegalStateException("DSH Runtime Bridge connection closed")));
        pending.clear();
    }

    static final class DshBridgeException extends IllegalStateException {
        private final int code;

        DshBridgeException(int code, String message) {
            super(message);
            this.code = code;
        }

        int code() {
            return code;
        }
    }
}

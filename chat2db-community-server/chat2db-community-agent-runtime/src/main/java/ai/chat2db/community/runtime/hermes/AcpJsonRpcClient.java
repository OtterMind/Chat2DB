package ai.chat2db.community.runtime.hermes;

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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class AcpJsonRpcClient implements Closeable {

    private static final int MAX_MESSAGE_CHARS = 4 * 1024 * 1024;

    @FunctionalInterface
    interface AgentRequestHandler {
        JsonNode handle(JsonNode requestId, String method, JsonNode params);
    }

    private final ObjectMapper mapper;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Consumer<JsonNode> notificationConsumer;
    private final AgentRequestHandler requestHandler;
    private final Consumer<Throwable> failureConsumer;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1L);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeMonitor = new Object();

    AcpJsonRpcClient(ObjectMapper mapper, InputStream input, OutputStream output,
                     Consumer<JsonNode> notificationConsumer, AgentRequestHandler requestHandler,
                     Consumer<Throwable> failureConsumer) {
        this.mapper = mapper;
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.notificationConsumer = notificationConsumer;
        this.requestHandler = requestHandler;
        this.failureConsumer = failureConsumer;
        Thread readerThread = new Thread(this::readLoop, "chat2db-hermes-acp-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    JsonNode request(String method, JsonNode params, Duration timeout) {
        return request(method, params, timeout, () -> false);
    }

    JsonNode request(String method, JsonNode params, Duration timeout, BooleanSupplier pauseTimeout) {
        if (closed.get()) {
            throw new IllegalStateException("Hermes ACP connection is closed");
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
            long remainingMillis = timeout.toMillis();
            while (remainingMillis > 0L) {
                long sliceMillis = Math.min(250L, remainingMillis);
                long startedAt = System.nanoTime();
                try {
                    return response.get(sliceMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    if (!pauseTimeout.getAsBoolean()) {
                        long elapsedMillis = Math.max(1L,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                        remainingMillis -= elapsedMillis;
                    }
                }
            }
            pending.remove(id);
            throw new IllegalStateException("Hermes ACP request timed out: " + method);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Hermes ACP request failed: " + method, cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Hermes ACP request interrupted: " + method, exception);
        } catch (RuntimeException exception) {
            pending.remove(id);
            throw exception;
        }
    }

    void notify(String method, JsonNode params) {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params == null ? mapper.createObjectNode() : params);
        write(message);
    }

    private void readLoop() {
        try {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.length() > MAX_MESSAGE_CHARS) {
                    throw new IOException("Hermes ACP message exceeds size limit");
                }
                dispatch(mapper.readTree(line));
            }
            if (!closed.get()) {
                failConnection(new IOException("Hermes ACP stream closed"));
            }
        } catch (Throwable failure) {
            if (!closed.get()) {
                failConnection(failure);
            }
        }
    }

    private void dispatch(JsonNode message) {
        JsonNode id = message.get("id");
        if (id != null && (message.has("result") || message.has("error"))) {
            CompletableFuture<JsonNode> future = pending.remove(id.asLong());
            if (future == null) {
                return;
            }
            if (message.has("error")) {
                future.completeExceptionally(new IllegalStateException(
                        "Hermes ACP error: " + message.get("error")));
            } else {
                future.complete(message.get("result"));
            }
            return;
        }
        if (message.hasNonNull("method") && id != null) {
            reply(id, message.path("method").asText(), message.path("params"));
        } else if (message.hasNonNull("method")) {
            notificationConsumer.accept(message);
        }
    }

    private void reply(JsonNode id, String method, JsonNode params) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        try {
            JsonNode result = requestHandler.handle(id, method, params);
            response.set("result", result == null ? mapper.createObjectNode() : result);
        } catch (RuntimeException exception) {
            ObjectNode error = response.putObject("error");
            error.put("code", -32601);
            error.put("message", "Unsupported Hermes ACP request: " + method);
        }
        write(response);
    }

    private void write(JsonNode message) {
        if (closed.get()) {
            throw new IllegalStateException("Hermes ACP connection is closed");
        }
        synchronized (writeMonitor) {
            try {
                writer.write(mapper.writeValueAsString(message));
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write Hermes ACP message", exception);
            }
        }
    }

    private void failConnection(Throwable failure) {
        pending.values().forEach(future -> future.completeExceptionally(failure));
        pending.clear();
        failureConsumer.accept(failure);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // The owning adapter terminates the process after closing ACP stdin.
        }
        pending.values().forEach(future -> future.completeExceptionally(
                new IllegalStateException("Hermes ACP connection closed")));
        pending.clear();
    }
}

package ai.chat2db.community.runtime.codex;

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

public class CodexJsonRpcClient implements Closeable {

    private static final int MAX_MESSAGE_CHARS = 4 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Consumer<JsonNode> notificationConsumer;
    private final CodexServerRequestHandler serverRequestHandler;
    private final Consumer<Throwable> failureConsumer;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1L);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeMonitor = new Object();
    private final Thread readerThread;

    public CodexJsonRpcClient(ObjectMapper mapper, InputStream input, OutputStream output,
                              Consumer<JsonNode> notificationConsumer,
                              CodexServerRequestHandler serverRequestHandler,
                              Consumer<Throwable> failureConsumer) {
        this.mapper = mapper;
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.notificationConsumer = notificationConsumer;
        this.serverRequestHandler = serverRequestHandler;
        this.failureConsumer = failureConsumer;
        this.readerThread = new Thread(this::readLoop, "chat2db-codex-jsonrpc-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    public JsonNode request(String method, JsonNode params, Duration timeout) {
        if (closed.get()) {
            throw new IllegalStateException("Codex JSON-RPC connection is closed");
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
            throw new IllegalStateException("Codex JSON-RPC request timed out: " + method, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Codex JSON-RPC request failed: " + method, cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Codex JSON-RPC request interrupted: " + method, exception);
        } catch (RuntimeException exception) {
            pending.remove(id);
            throw exception;
        }
    }

    public void notify(String method, JsonNode params) {
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
                    throw new IOException("Codex JSON-RPC message exceeds size limit");
                }
                dispatch(mapper.readTree(line));
            }
            if (!closed.get()) {
                failConnection(new IOException("Codex JSON-RPC stream closed"));
            }
        } catch (Throwable failure) {
            if (!closed.get()) {
                failConnection(failure);
            }
        }
    }

    private void dispatch(JsonNode message) {
        JsonNode idNode = message.get("id");
        if (idNode != null && (message.has("result") || message.has("error"))) {
            long id = idNode.asLong();
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future == null) {
                return;
            }
            if (message.has("error")) {
                future.completeExceptionally(new IllegalStateException(
                        "Codex JSON-RPC error: " + message.get("error")));
            } else {
                future.complete(message.get("result"));
            }
            return;
        }
        if (message.hasNonNull("method") && idNode != null) {
            handleServerRequest(idNode, message.path("method").asText(), message.path("params"));
            return;
        }
        if (message.hasNonNull("method")) {
            notificationConsumer.accept(message);
        }
    }

    private void handleServerRequest(JsonNode id, String method, JsonNode params) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        try {
            JsonNode result = serverRequestHandler.handle(method, params);
            response.set("result", result == null ? mapper.createObjectNode() : result);
        } catch (RuntimeException exception) {
            ObjectNode error = mapper.createObjectNode();
            error.put("code", -32601);
            error.put("message", "Unsupported Codex server request: " + method);
            response.set("error", error);
        }
        write(response);
    }

    private void write(JsonNode message) {
        if (closed.get()) {
            throw new IllegalStateException("Codex JSON-RPC connection is closed");
        }
        synchronized (writeMonitor) {
            try {
                writer.write(mapper.writeValueAsString(message));
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write Codex JSON-RPC message", exception);
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
            // Closing stdin is best effort; the owning process is terminated separately.
        }
        // Do not close the BufferedReader here. Another thread can be blocked in
        // readLine() while holding its decoder lock. The owning process is
        // terminated immediately after this connection closes, which closes
        // stdout and lets the daemon reader exit without a cross-thread deadlock.
        pending.values().forEach(future -> future.completeExceptionally(
                new IllegalStateException("Codex JSON-RPC connection closed")));
        pending.clear();
    }
}

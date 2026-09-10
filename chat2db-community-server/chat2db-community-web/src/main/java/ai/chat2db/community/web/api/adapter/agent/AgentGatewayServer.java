package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.service.agent.AgentToolAccessService;
import ai.chat2db.community.web.api.model.request.agent.AgentToolRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/** A loopback listener exposing only the Agent tool and model transports. */
public final class AgentGatewayServer implements AutoCloseable {
    private static final String TOOLS = "/api/v3/ai/agent-tools/";
    private static final String MODEL = "/api/v3/ai/agent-model/v1/responses";
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private final AgentGatewayAddress address;
    private final Supplier<AgentToolAccessService> tools;
    private final Supplier<IAgentModelGateway> models;
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;

    public AgentGatewayServer(AgentGatewayAddress address, Supplier<AgentToolAccessService> tools,
            Supplier<IAgentModelGateway> models) {
        this.address = address;
        this.tools = tools;
        this.models = models;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        HttpServer listener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "chat2db-agent-http");
            thread.setDaemon(true);
            return thread;
        });
        try {
            listener.setExecutor(executor);
            listener.createContext("/", this::handle);
            listener.start();
            server = listener;
            address.publish(listener.getAddress().getPort());
            LoggerFactory.getLogger(AgentGatewayServer.class).info("Agent internal gateway listening on {}", address.baseUrl());
        } catch (RuntimeException | Error failure) {
            listener.stop(0);
            executor.shutdownNow();
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        address.clear();
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            boolean catalog = (TOOLS + "catalog").equals(path) && "GET".equals(method);
            boolean execute = (TOOLS + "execute").equals(path) && "POST".equals(method);
            boolean nativeTool = (TOOLS + "prepare-native").equals(path) && "POST".equals(method);
            boolean model = MODEL.equals(path) && "POST".equals(method);
            if (!catalog && !execute && !nativeTool && !model) {
                writeJson(exchange, 404, Map.of("success", false, "errorMessage", "Unknown Agent endpoint"));
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
                throw new SecurityException("Agent ticket is required");
            }
            String ticket = authorization.substring(7);
            String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (catalog) {
                writeJson(exchange, 200, tools.get().activeTools(ticket, remote));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                writeJson(exchange, 413, Map.of("success", false, "errorMessage", "Agent request is too large"));
                return;
            }
            if (model) {
                try (var response = models.get().forward(ticket, remote, body)) {
                    exchange.getResponseHeaders().set("Content-Type", response.contentType());
                    exchange.sendResponseHeaders(response.statusCode(), 0);
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = response.body().read(buffer)) != -1) {
                        exchange.getResponseBody().write(buffer, 0, count);
                        exchange.getResponseBody().flush();
                    }
                }
                return;
            }
            AgentToolRequest request = json.readValue(body, AgentToolRequest.class);
            if (request == null || request.toolCallId() == null || request.toolCallId().isBlank()
                    || request.toolCallId().length() > 200 || request.toolName() == null || request.toolName().isBlank()
                    || request.toolName().length() > 100 || request.arguments() == null) {
                throw new IllegalArgumentException("Invalid Agent tool request");
            }
            Object result = nativeTool
                    ? tools.get().prepareNative(ticket, remote, request.toolCallId(), request.toolName(), request.arguments())
                    : Map.of("success", true, "data", tools.get().execute(ticket, remote,
                            request.toolCallId(), request.toolName(), request.arguments()));
            writeJson(exchange, 200, result);
        } catch (Exception error) {
            if (exchange.getResponseCode() < 0) {
                int status = error instanceof SecurityException ? 403 : error instanceof IllegalArgumentException ? 400 : 502;
                writeJson(exchange, status, Map.of("success", false, "errorMessage",
                        error.getMessage() == null ? "Agent request failed" : error.getMessage()));
            }
        } finally {
            exchange.close();
        }
    }

    private void writeJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = json.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}

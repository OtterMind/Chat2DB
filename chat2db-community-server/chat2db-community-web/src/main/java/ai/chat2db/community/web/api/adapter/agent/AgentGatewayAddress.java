package ai.chat2db.community.web.api.adapter.agent;

import org.springframework.stereotype.Component;

/** Address of the bound Agent-only listener, independent of the application's HTTP server. */
@Component
public class AgentGatewayAddress {
    private volatile String baseUrl;

    public String baseUrl() {
        String address = baseUrl;
        if (address == null) throw new IllegalStateException("Agent internal gateway is not running");
        return address;
    }

    void publish(int port) {
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Invalid bound Agent port");
        baseUrl = "http://127.0.0.1:" + port;
    }

    void clear() {
        baseUrl = null;
    }
}

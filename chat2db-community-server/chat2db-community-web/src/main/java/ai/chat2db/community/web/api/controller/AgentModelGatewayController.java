package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.adapter.agent.IAgentModelGateway;
import ai.chat2db.community.web.api.model.response.agent.AgentModelGatewayResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class AgentModelGatewayController {

    private final IAgentModelGateway gatewayService;

    public AgentModelGatewayController(IAgentModelGateway gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/api/v3/ai/agent-model/v1/responses")
    public ResponseEntity<StreamingResponseBody> responses(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody byte[] body,
            HttpServletRequest request) {
        AgentModelGatewayResponse upstream;
        try {
            upstream = gatewayService.forward(bearerToken(authorization), request.getRemoteAddr(),
                    "/v1/responses", Map.of(), body);
        } catch (IOException error) {
            byte[] failure = """
                    {"error":{"type":"model_connection_failed","message":"Cannot connect to the configured model endpoint. Check the model URL and service availability."}}
                    """.getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(502).header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(output -> output.write(failure));
        }
        StreamingResponseBody responseBody = output -> {
            try (upstream) {
                upstream.body().transferTo(output);
            }
        };
        return ResponseEntity.status(upstream.statusCode())
                .header(HttpHeaders.CONTENT_TYPE, upstream.contentType())
                .body(responseBody);
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= "Bearer ".length()) {
            throw new SecurityException("Agent model authorization is invalid");
        }
        return authorization.substring("Bearer ".length());
    }
}

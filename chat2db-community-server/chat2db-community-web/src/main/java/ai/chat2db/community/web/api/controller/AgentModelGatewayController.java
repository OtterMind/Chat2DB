package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.adapter.agent.AgentModelGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;

@RestController
public class AgentModelGatewayController {

    private final AgentModelGatewayService gatewayService;

    public AgentModelGatewayController(AgentModelGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/api/v3/ai/agent-model/v1/responses")
    public ResponseEntity<StreamingResponseBody> responses(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody byte[] body,
            HttpServletRequest request) throws IOException {
        AgentModelGatewayService.GatewayResponse upstream = gatewayService.forward(
                bearerToken(authorization), request.getRemoteAddr(), body);
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

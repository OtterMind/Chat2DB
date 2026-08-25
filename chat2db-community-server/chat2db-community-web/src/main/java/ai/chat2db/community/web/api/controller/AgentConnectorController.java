package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.enums.agent.AgentConnectorPairingStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorPairing;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorPairingTicket;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorContext;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorConversation;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeTaskScope;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorSession;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorTokenGrant;
import ai.chat2db.community.domain.api.model.request.agent.AgentConnectorPairingCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentConnectorPairingDecisionRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentConnectorService;
import ai.chat2db.community.domain.api.service.agent.IAgentToolGateway;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/connectors")
public class AgentConnectorController {
    private static final String EXTERNAL_SESSION_HEADER = "x-chat2db-external-session-id";
    private static final String EXTERNAL_CALL_HEADER = "x-chat2db-external-call-id";
    private final IAgentConnectorService connectorService;
    private final IAgentToolGateway toolGateway;
    private final IIdentityService identityService;
    private final AgentRuntimeMcpController mcpController;

    public AgentConnectorController(IAgentConnectorService connectorService, IAgentToolGateway toolGateway,
                                    IIdentityService identityService, AgentRuntimeMcpController mcpController) {
        this.connectorService = connectorService;
        this.toolGateway = toolGateway;
        this.identityService = identityService;
        this.mcpController = mcpController;
    }

    @PostMapping("/pairings")
    public Map<String, Object> createPairing(@RequestBody AgentConnectorPairingCreateRequest request) {
        AgentConnectorPairingTicket ticket = connectorService.createPairing(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pairingId", ticket.getPairingId());
        result.put("pollToken", ticket.getPollToken());
        result.put("userCode", ticket.getUserCode());
        result.put("expiresAt", instant(ticket.getExpiresAt()));
        result.put("pollAfterMs", ticket.getPollAfterMs());
        return result;
    }

    @GetMapping("/pairings/{pairingId}")
    public Map<String, Object> pairingStatus(@PathVariable String pairingId,
            @RequestHeader("x-chat2db-poll-token") String pollToken) {
        AgentConnectorPairing pairing = connectorService.pairingStatus(pairingId, pollToken);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", pairing.getStatus().name().toLowerCase());
        if (pairing.getStatus() == AgentConnectorPairingStatusEnum.APPROVED) {
            result.put("exchangeCode", pairing.getExchangeCode());
        }
        return result;
    }

    @PostMapping("/pairings/{pairingId}/exchange")
    public Map<String, Object> exchange(@PathVariable String pairingId, @RequestBody Map<String, String> request) {
        return grant(connectorService.exchange(pairingId, request.get("pollToken"), request.get("exchangeCode")));
    }

    @GetMapping("/pairings/pending")
    public List<Map<String, Object>> pendingPairings() {
        return connectorService.listPendingPairings().stream().map(this::pairingView).toList();
    }

    @PostMapping("/pairings/{pairingId}/decision")
    public Map<String, Object> decidePairing(@PathVariable String pairingId,
                                             @RequestBody AgentConnectorPairingDecisionRequest request) {
        if (request == null || request.getApproved() == null || request.getExpectedRevision() == null) {
            throw new IllegalArgumentException("pairing decision, approved and expectedRevision are required");
        }
        return pairingView(connectorService.decidePairing(pairingId, request.getAgentId(), request.getApproved(),
                request.getExpectedRevision(), identityService.currentUserId()));
    }

    @PostMapping("/sessions/{sessionId}/refresh")
    public Map<String, Object> refresh(@PathVariable String sessionId, @RequestBody Map<String, String> request) {
        return grant(connectorService.refresh(sessionId, request.get("refreshToken")));
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        return connectorService.listSessions(identityService.currentUserId()).stream().map(this::sessionView).toList();
    }

    @GetMapping("/sessions/{sessionId}/context")
    public ResponseEntity<AgentConnectorContext> context(@PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        try {
            return ResponseEntity.ok(connectorService.context(sessionId, bearer(authorization)));
        } catch (SecurityException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @GetMapping("/sessions/{sessionId}/conversations")
    public List<Map<String, Object>> conversations(@PathVariable String sessionId) {
        return connectorService.listConversations(sessionId, identityService.currentUserId()).stream()
                .map(this::conversationView).toList();
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    public Map<String, Object> revoke(@PathVariable String sessionId) {
        return sessionView(connectorService.revoke(sessionId, identityService.currentUserId()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        connectorService.deleteSession(sessionId, identityService.currentUserId());
        return Map.of("deleted", true);
    }

    @GetMapping("/sessions/{sessionId}/approvals/{approvalId}")
    public ResponseEntity<Map<String, Object>> approval(@PathVariable String sessionId, @PathVariable String approvalId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = EXTERNAL_SESSION_HEADER, required = false) String externalSessionId,
            @RequestHeader(value = EXTERNAL_CALL_HEADER, required = false) String externalCallId) {
        try {
            var scope = connectorService.authorizeInvocation(sessionId, bearer(authorization),
                    externalSessionId, externalCallId);
            AgentApproval approval = toolGateway.getApproval(approvalId);
            if (!scope.getRunId().equals(approval.getRunId())) throw new SecurityException("approval scope mismatch");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("approvalId", approval.getId());
            result.put("status", approval.getStatus().name().toLowerCase());
            result.put("revision", approval.getRevision());
            result.put("reason", approval.getReason());
            return ResponseEntity.ok(result);
        } catch (SecurityException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping(value = "/mcp/sessions/{sessionId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> mcp(@PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = EXTERNAL_SESSION_HEADER, required = false) String externalSessionId,
            @RequestHeader(value = EXTERNAL_CALL_HEADER, required = false) String externalCallId,
            @RequestBody JsonNode message) {
        AgentRuntimeTaskScope scope = null;
        try {
            if (!"tools/call".equals(message == null ? null : message.path("method").asText(null))) {
                return mcpController.handleAuthorized(connectorService.authorizeAccess(sessionId,
                        bearer(authorization)), message);
            }
            JsonNode params = message.path("params");
            scope = connectorService.authorizeToolCall(sessionId, bearer(authorization),
                    externalSessionId, externalCallId, params.path("name").asText(null),
                    params.path("arguments").toString());
            if (StringUtils.isNotBlank(scope.getConnectorReplayResultJson())) {
                return mcpController.replay(message.get("id"), scope.getConnectorReplayResultJson());
            }
            ResponseEntity<JsonNode> response = mcpController.handleAuthorized(scope, message);
            JsonNode result = response.getBody() == null ? null : response.getBody().get("result");
            if (result != null) {
                boolean failed = result.path("isError").asBoolean(false);
                boolean waitingApproval = "approval_required".equals(
                        result.path("structuredContent").path("kind").asText());
                connectorService.completeToolCall(scope, !failed, waitingApproval, result.toString());
            }
            return response;
        } catch (SecurityException exception) {
            return mcpController.authorizationFailure(message == null ? null : message.get("id"));
        }
    }

    private Map<String, Object> pairingView(AgentConnectorPairing value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pairingId", value.getId()); result.put("clientName", value.getClientName());
        result.put("userCode", value.getUserCode()); result.put("status", value.getStatus().name().toLowerCase());
        result.put("agentId", value.getAgentId()); result.put("agentName", value.getAgentName());
        result.put("expiresAt", instant(value.getExpiresAt())); result.put("createdAt", instant(value.getCreatedAt()));
        result.put("revision", value.getRevision()); return result;
    }

    private Map<String, Object> sessionView(AgentConnectorSession value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", value.getId()); result.put("clientName", value.getClientName());
        result.put("agentId", value.getAgentId()); result.put("agentName", value.getAgentName());
        result.put("taskId", value.getTaskId()); result.put("runId", value.getRunId());
        result.put("status", value.getStatus().name().toLowerCase());
        result.put("pendingApprovalCount", value.getPendingApprovalCount() == null ? 0 : value.getPendingApprovalCount());
        result.put("createdAt", instant(value.getCreatedAt())); result.put("lastUsedAt", instant(value.getLastUsedAt()));
        result.put("refreshTokenExpiresAt", instant(value.getRefreshTokenExpiresAt()));
        result.put("revokedAt", instant(value.getRevokedAt()));
        result.put("legacyAudit", StringUtils.isNotBlank(value.getTaskId()));
        result.put("conversationCount", value.getConversationCount() == null ? 0 : value.getConversationCount());
        return result;
    }

    private Map<String, Object> conversationView(AgentConnectorConversation value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", value.getId());
        result.put("externalSessionId", value.getExternalSessionId());
        result.put("taskId", value.getTaskId());
        result.put("status", value.getStatus().name().toLowerCase());
        result.put("pendingApprovalCount", value.getPendingApprovalCount() == null ? 0 : value.getPendingApprovalCount());
        result.put("createdAt", instant(value.getCreatedAt()));
        result.put("lastUsedAt", instant(value.getLastUsedAt()));
        result.put("closedAt", instant(value.getClosedAt()));
        return result;
    }

    private Map<String, Object> grant(AgentConnectorTokenGrant value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", value.getSessionId()); result.put("agentId", value.getAgentId());
        result.put("agentName", value.getAgentName()); result.put("mcpEndpoint", value.getMcpEndpoint());
        result.put("accessToken", value.getAccessToken());
        result.put("accessTokenExpiresAt", instant(value.getAccessTokenExpiresAt()));
        result.put("refreshToken", value.getRefreshToken());
        result.put("refreshTokenExpiresAt", instant(value.getRefreshTokenExpiresAt()));
        return result;
    }

    private String bearer(String authorization) {
        return StringUtils.startsWith(authorization, "Bearer ")
                ? StringUtils.trimToNull(authorization.substring("Bearer ".length())) : null;
    }

    private String instant(java.util.Date value) {
        return value == null ? null : Instant.ofEpochMilli(value.getTime()).toString();
    }
}

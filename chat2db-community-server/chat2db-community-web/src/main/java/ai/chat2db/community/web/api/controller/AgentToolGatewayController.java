package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.agent.AgentApprovalService;
import ai.chat2db.community.domain.api.service.agent.AgentApprovalStorage;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentApprovalStatus;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.web.api.adapter.agent.AgentToolGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v3/ai")
public class AgentToolGatewayController {
    private final AgentToolGatewayService gateway;
    private final AgentApprovalService approvals;
    private final AgentApprovalStorage approvalStorage;
    private final IIdentityService identity;

    public AgentToolGatewayController(AgentToolGatewayService gateway, AgentApprovalService approvals,
            AgentApprovalStorage approvalStorage,
            IIdentityService identity) {
        this.gateway = gateway;
        this.approvals = approvals;
        this.approvalStorage = approvalStorage;
        this.identity = identity;
    }

    @GetMapping("/agent-tools/catalog")
    public List<String> catalog(@RequestHeader("Authorization") String authorization, HttpServletRequest request) {
        return gateway.activeTools(ticket(authorization), request.getRemoteAddr());
    }

    @PostMapping("/agent-tools/execute")
    public ResponseEntity<Map<String, String>> execute(@RequestHeader("Authorization") String authorization,
            @RequestBody @Valid ToolRequest body, HttpServletRequest request) throws Exception {
        try {
            return ResponseEntity.ok(Map.of("content", gateway.execute(ticket(authorization), request.getRemoteAddr(),
                    body.toolCallId(), body.toolName(), body.arguments())));
        } catch (IllegalStateException error) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", error.getMessage()));
        }
    }

    @PostMapping("/sessions/{sessionId}/approvals")
    public ActionResult decide(@PathVariable String sessionId,
            @RequestBody @Valid DecisionRequest decision) {
        approvals.decide(sessionId, decision.approvalId(), identity.currentUserId(), decision.approved());
        return ActionResult.isSuccess();
    }

    @GetMapping("/sessions/{sessionId}/approvals")
    public ListResult<AgentApproval> pending(@PathVariable String sessionId) {
        return ListResult.of(approvalStorage.list(sessionId, identity.currentUserId()).stream()
                .filter(approval -> approval.status() == AgentApprovalStatus.PENDING
                        && approval.expiresAt().isAfter(java.time.LocalDateTime.now())).toList());
    }

    private String ticket(String authorization) {
        if (!authorization.startsWith("Bearer ")) throw new SecurityException("Agent ticket is required");
        return authorization.substring(7);
    }

    public record ToolRequest(@NotBlank @Size(max = 200) String toolCallId,
            @NotBlank @Size(max = 100) String toolName, @NotNull Map<String, Object> arguments) { }
    public record DecisionRequest(@NotBlank String approvalId, @NotNull Boolean approved) { }
}

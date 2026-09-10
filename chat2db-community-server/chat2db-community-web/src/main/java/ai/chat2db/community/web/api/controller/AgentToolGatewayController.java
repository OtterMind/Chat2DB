package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatus;
import ai.chat2db.community.web.api.model.request.agent.AgentToolRequest;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.interaction.AgentQuestion;
import ai.chat2db.community.domain.api.service.agent.AgentApprovalService;
import ai.chat2db.community.domain.api.service.agent.AgentApprovalStorage;
import ai.chat2db.community.domain.api.service.agent.AgentToolAccessService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentQuestionService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.agent.tool.IAgentToolResult;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v3/ai")
public class AgentToolGatewayController {
    private final AgentToolAccessService gateway;
    private final AgentApprovalService approvals;
    private final AgentApprovalStorage approvalStorage;
    private final IIdentityService identity;
    private final IAiAgentQuestionService questions;

    public AgentToolGatewayController(AgentToolAccessService gateway, AgentApprovalService approvals,
            AgentApprovalStorage approvalStorage,
            IIdentityService identity, IAiAgentQuestionService questions) {
        this.gateway = gateway;
        this.approvals = approvals;
        this.approvalStorage = approvalStorage;
        this.identity = identity;
        this.questions = questions;
    }

    @GetMapping("/agent-tools/catalog")
    public List<String> catalog(@RequestHeader("Authorization") String authorization, HttpServletRequest request) {
        return gateway.activeTools(ticket(authorization), request.getRemoteAddr());
    }

    @PostMapping("/agent-tools/execute")
    public DataResult<IAgentToolResult<?>> execute(@RequestHeader("Authorization") String authorization,
            @RequestBody @Valid AgentToolRequest body, HttpServletRequest request) throws Exception {
        return DataResult.of(gateway.execute(ticket(authorization), request.getRemoteAddr(),
                body.toolCallId(), body.toolName(), body.arguments()));
    }

    @PostMapping("/agent-tools/prepare-native")
    public ai.chat2db.community.domain.api.model.agent.feature.AgentWorkspaceSettings prepareNative(
            @RequestHeader("Authorization") String authorization, @RequestBody @Valid AgentToolRequest body,
            HttpServletRequest request) throws Exception {
        return gateway.prepareNative(ticket(authorization), request.getRemoteAddr(),
                body.toolCallId(), body.toolName(), body.arguments());
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

    @GetMapping("/sessions/{sessionId}/questions")
    public ListResult<AgentQuestion> pendingQuestions(@PathVariable String sessionId) {
        return ListResult.of(questions.pending(sessionId, identity.currentUserId()));
    }

    @PostMapping("/sessions/{sessionId}/questions/answer")
    public DataResult<AgentQuestion.Answer> answerQuestion(@PathVariable String sessionId,
            @RequestBody @Valid QuestionAnswerRequest answer) {
        return DataResult.of(questions.answer(sessionId, answer.questionId(), identity.currentUserId(),
                new AgentQuestion.Response(answer.optionId(), answer.text())));
    }

    public record QuestionAnswerRequest(@NotBlank String questionId, @Size(max = 64) String optionId, @Size(max = 4000) String text) { }

    private String ticket(String authorization) {
        if (!authorization.startsWith("Bearer ")) throw new SecurityException("Agent ticket is required");
        return authorization.substring(7);
    }

    public record DecisionRequest(@NotBlank String approvalId, @NotNull Boolean approved) { }
}

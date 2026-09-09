package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.ai.AiSessionSummary;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunCancelCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentSessionCreateCommand;
import ai.chat2db.community.domain.api.service.agent.AgentService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.domain.api.service.ai.AiSessionFacadeService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.web.api.adapter.agent.AgentHostEnvironmentProvider;
import ai.chat2db.community.web.api.model.request.agent.AgentRunCancelRequest;
import ai.chat2db.community.web.api.model.request.agent.AgentRunStartRequest;
import ai.chat2db.community.web.api.model.request.agent.AgentSessionCreateRequest;
import ai.chat2db.community.web.api.model.response.agent.AgentEventResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletionStage;

@RestController
@RequestMapping("/api/v3/ai")
public class AgentController {

    private final AgentService agentService;
    private final IIdentityService identityService;
    private final AgentHostEnvironmentProvider environmentProvider;
    private final AiSessionFacadeService sessionFacadeService;

    public AgentController(
            AgentService agentService,
            IIdentityService identityService,
            AgentHostEnvironmentProvider environmentProvider,
            AiSessionFacadeService sessionFacadeService) {
        this.agentService = agentService;
        this.identityService = identityService;
        this.environmentProvider = environmentProvider;
        this.sessionFacadeService = sessionFacadeService;
    }

    @PostMapping("/sessions")
    public DataResult<AgentSession> createSession(@RequestBody @Valid AgentSessionCreateRequest request) {
        requireV2(request.sessionVersion());
        return DataResult.of(agentService.createSession(new AgentSessionCreateCommand(
                identityService.currentUserId(), request.title(), request.definition(), environmentProvider.current())));
    }

    @GetMapping("/sessions")
    public ListResult<AiSessionSummary> listSessions() {
        return ListResult.of(sessionFacadeService.listSessions(identityService.currentUserId()));
    }

    @GetMapping("/sessions/{sessionId}")
    public DataResult<AiSessionSummary> getSession(
            @PathVariable String sessionId,
            @RequestParam int sessionVersion) {
        AiSessionSummary session = sessionFacadeService.getSession(
                sessionId, identityService.currentUserId(), sessionVersion);
        if (session == null) {
            throw new IllegalArgumentException("Agent session does not exist");
        }
        return DataResult.of(session);
    }

    @PostMapping("/sessions/{sessionId}/runs")
    public CompletionStage<DataResult<AgentRun>> startRun(
            @PathVariable String sessionId,
            @RequestBody @Valid AgentRunStartRequest request) {
        return agentService.startRun(new AgentRunStartCommand(
                        identityService.currentUserId(), sessionId,
                        request.model(), request.input(), request.idempotencyKey()))
                .thenApply(DataResult::of);
    }

    @PostMapping("/runs/{runId}/cancel")
    public CompletionStage<DataResult<AgentRun>> cancelRun(
            @PathVariable String runId,
            @RequestBody @Valid AgentRunCancelRequest request) {
        return agentService.cancelRun(new AgentRunCancelCommand(
                        identityService.currentUserId(), request.sessionId(), runId))
                .thenApply(DataResult::of);
    }

    @GetMapping("/sessions/{sessionId}/events")
    public ListResult<AgentEventResponse> listEvents(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "200") int limit) {
        List<AgentEvent> events = agentService.listEvents(
                sessionId, identityService.currentUserId(), afterSequence, limit);
        return ListResult.of(events.stream().map(AgentEventResponse::from).toList());
    }

    private void requireV2(Integer sessionVersion) {
        if (sessionVersion != AgentSession.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Agent sessions require sessionVersion 2");
        }
    }
}

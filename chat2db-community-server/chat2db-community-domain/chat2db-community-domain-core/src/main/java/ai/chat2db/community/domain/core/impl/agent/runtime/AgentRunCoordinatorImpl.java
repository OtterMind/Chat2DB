package ai.chat2db.community.domain.core.impl.agent.runtime;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDetail;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskContext;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeResumeRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunTransitionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskTransitionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactCreateRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentArtifactService;
import ai.chat2db.community.domain.api.service.agent.IAgentContextAssembler;
import ai.chat2db.community.domain.api.service.agent.IAgentDefinitionService;
import ai.chat2db.community.domain.api.service.agent.IAgentRunCoordinator;
import ai.chat2db.community.domain.api.service.agent.IAgentRunService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskService;
import ai.chat2db.community.domain.api.service.agent.runtime.AgentRuntime;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentRunCoordinatorImpl implements IAgentRunCoordinator {

    private static final String DSML_TOOL_CALL_MARKER = "<｜｜DSML｜｜tool_calls>";
    private static final String XML_TOOL_CALL_MARKER = "<tool_calls>";
    private static final String PSEUDO_TOOL_CALL_FAILURE =
            "Agent returned pseudo tool-call markup; no database tool was executed. "
                    + "Use a model endpoint with native tool-calling support.";
    private static final int MAX_RESULT_SUMMARY_LENGTH = 20_000;

    private final IAgentRunService runService;
    private final IAgentTaskService taskService;
    private final IAgentDefinitionService agentService;
    private final IAgentContextAssembler contextAssembler;
    private final IAgentArtifactService artifactService;
    private final IAgentControlStorage storage;
    private final AgentRuntimeRegistry runtimeRegistry;

    public AgentRunCoordinatorImpl(IAgentRunService runService, IAgentTaskService taskService,
                                   IAgentDefinitionService agentService, IAgentContextAssembler contextAssembler,
                                   IAgentArtifactService artifactService, IAgentControlStorage storage,
                                   AgentRuntimeRegistry runtimeRegistry) {
        this.runService = runService;
        this.taskService = taskService;
        this.agentService = agentService;
        this.contextAssembler = contextAssembler;
        this.artifactService = artifactService;
        this.storage = storage;
        this.runtimeRegistry = runtimeRegistry;
    }

    @Override
    public AgentRun dispatch(String runId) {
        AgentRun queued = runService.get(runId);
        if (queued.getStatus() != AgentRunStatusEnum.QUEUED) {
            throw new IllegalStateException("only queued runs can be dispatched");
        }
        AgentTask task = taskService.get(queued.getTaskId());
        AgentDefinition agent = agentService.get(queued.getAgentId());
        AgentRuntime runtime = runtimeRegistry.require(queued.getRuntimeType());

        transitionRun(queued, AgentRunStatusEnum.DISPATCHED, null, null);
        persistStatus(runId, "DISPATCHED");
        AgentRun running = transitionRun(runService.get(runId), AgentRunStatusEnum.RUNNING, null, null);
        transitionTaskIf(task.getStatus(), task, AgentTaskStatusEnum.TODO, AgentTaskStatusEnum.IN_PROGRESS);
        persistStatus(runId, "RUNNING");

        AgentRuntimeStartRequest request = runtimeStartRequest(running, task, agent);
        try {
            runtime.start(request, this::handleRuntimeEvent);
        } catch (RuntimeException exception) {
            AgentRuntimeEvent failure = new AgentRuntimeEvent();
            failure.setEventId(UUID.randomUUID().toString());
            failure.setRunId(runId);
            failure.setType(AgentRuntimeEventTypeEnum.ERROR);
            failure.setContent(StringUtils.defaultIfBlank(exception.getMessage(), "Agent runtime failed"));
            failure.setOccurredAt(new Date());
            handleRuntimeEvent(failure);
        }
        return runService.get(runId);
    }

    @Override
    public AgentRun resumeAfterApproval(String runId, String approvalContext) {
        AgentRun running = runService.get(runId);
        if (running.getStatus() != AgentRunStatusEnum.RUNNING) {
            throw new IllegalStateException("only a running approved run can be resumed");
        }
        AgentTask task = taskService.get(running.getTaskId());
        AgentDefinition agent = agentService.get(running.getAgentId());
        AgentRuntime runtime = runtimeRegistry.require(running.getRuntimeType());
        if (!runtime.capabilities().isApprovalResume()) {
            throw new IllegalStateException("agent runtime does not support approval resume");
        }

        AgentRuntimeStartRequest startRequest = runtimeStartRequest(running, task, agent);
        if (StringUtils.isNotBlank(approvalContext)) {
            startRequest.setAssembledContext(startRequest.getAssembledContext()
                    + "\n\n## Approved Action Context\n" + approvalContext.trim());
        }
        AgentRuntimeResumeRequest resumeRequest = new AgentRuntimeResumeRequest();
        resumeRequest.setRunId(runId);
        resumeRequest.setRuntimeExecutionId(runId);
        resumeRequest.setPayload(approvalContext);
        resumeRequest.setStartRequest(startRequest);
        try {
            persistStatus(runId, "RUNNING");
            runtime.resume(resumeRequest, this::handleRuntimeEvent);
        } catch (RuntimeException exception) {
            AgentRuntimeEvent failure = new AgentRuntimeEvent();
            failure.setEventId(UUID.randomUUID().toString());
            failure.setRunId(runId);
            failure.setType(AgentRuntimeEventTypeEnum.ERROR);
            failure.setContent(StringUtils.defaultIfBlank(exception.getMessage(), "Agent runtime resume failed"));
            failure.setOccurredAt(new Date());
            handleRuntimeEvent(failure);
        }
        return runService.get(runId);
    }

    @Override
    public AgentRun cancel(String runId) {
        AgentRun current = runService.get(runId);
        if (terminal(current.getStatus())) {
            return current;
        }
        runtimeRegistry.require(current.getRuntimeType()).cancel(runId);
        AgentRun cancelled = transitionRun(runService.get(runId), AgentRunStatusEnum.CANCELLED, null, null);
        persistStatus(runId, "CANCELLED");
        return cancelled;
    }

    private AgentRuntimeStartRequest runtimeStartRequest(AgentRun run, AgentTask task, AgentDefinition agent) {
        AgentRuntimeStartRequest request = new AgentRuntimeStartRequest();
        request.setAgent(agent);
        request.setTask(taskService.get(task.getId()));
        request.setRun(run);
        request.setCurrentInput(currentUserInput(run, task.getId()));
        request.setAssembledContext(contextAssembler.assemble(
                agent, request.getTask(), taskService.listRuns(task.getId())));
        return request;
    }

    private String currentUserInput(AgentRun run, String taskId) {
        if (run.getTriggerType() != AgentRunTriggerTypeEnum.USER_MESSAGE) {
            return null;
        }
        List<AgentTaskContext> contexts = storage.listTaskContexts(taskId);
        for (int index = contexts.size() - 1; index >= 0; index--) {
            AgentTaskContext entry = contexts.get(index);
            if (entry.getType() == AgentTaskContextTypeEnum.COMMENT
                    && StringUtils.isNotBlank(entry.getContent())) {
                return entry.getContent();
            }
        }
        return null;
    }

    @Override
    public List<AgentRunEvent> listEvents(String runId) {
        runService.get(runId);
        return storage.listRunEvents(runId);
    }

    private void handleRuntimeEvent(AgentRuntimeEvent event) {
        validateRuntimeEvent(event);
        if (event.getType() == AgentRuntimeEventTypeEnum.ERROR) {
            failRun(event.getRunId(), event.getContent());
            persistEvent(event);
            return;
        }
        if (event.getType() == AgentRuntimeEventTypeEnum.STATUS) {
            AgentRunStatusEnum target = runtimeStatus(event);
            if (target == AgentRunStatusEnum.COMPLETED
                    && runService.get(event.getRunId()).getStatus() == AgentRunStatusEnum.WAITING_APPROVAL) {
                return;
            }
            if (target != null) {
                if (target == AgentRunStatusEnum.COMPLETED && containsPseudoToolCall(event.getRunId())) {
                    failRun(event.getRunId(), PSEUDO_TOOL_CALL_FAILURE);
                    AgentRuntimeEvent protocolError = new AgentRuntimeEvent();
                    protocolError.setEventId(UUID.randomUUID().toString());
                    protocolError.setRunId(event.getRunId());
                    protocolError.setType(AgentRuntimeEventTypeEnum.ERROR);
                    protocolError.setContent(PSEUDO_TOOL_CALL_FAILURE);
                    protocolError.setOccurredAt(new Date());
                    persistEvent(protocolError);
                    return;
                }
                convergeRun(event.getRunId(), target, event.getContent());
            }
            persistEvent(event);
            if (target == AgentRunStatusEnum.COMPLETED) {
                finalizeCompletedRun(event.getRunId());
            }
            return;
        }
        persistEvent(event);
    }

    private void convergeRun(String runId, AgentRunStatusEnum target, String message) {
        AgentRun current = runService.get(runId);
        if (terminal(current.getStatus()) || current.getStatus() == target) {
            return;
        }
        String resultSummary = target == AgentRunStatusEnum.COMPLETED ? completedAnswerSummary(runId) : null;
        transitionRun(current, target, target == AgentRunStatusEnum.FAILED ? message : null, resultSummary);
    }

    private void finalizeCompletedRun(String runId) {
        AgentRun run = runService.get(runId);
        AgentTask task = taskService.get(run.getTaskId());
        AgentDefinition agent = agentService.get(run.getAgentId());
        String markdown = completedAnswer(runId);
        if (StringUtils.isNotBlank(markdown)) {
            AgentArtifactCreateRequest artifact = new AgentArtifactCreateRequest();
            artifact.setTaskId(task.getId());
            artifact.setType(AgentArtifactTypeEnum.REPORT);
            artifact.setTitle(task.getTitle() + " - Analysis Report");
            artifact.setStatus(AgentArtifactStatusEnum.READY);
            artifact.setCreatedByRunId(runId);
            artifact.setCreatedBy(task.getCreatedBy());
            artifact.setContent(Map.of(
                    "artifactType", AgentArtifactTypeEnum.REPORT.name(),
                    "blocks", List.of(Map.of("type", "MARKDOWN", "content", markdown))));
            AgentArtifactDetail created = artifactService.create(artifact);
            AgentRuntimeEvent artifactEvent = new AgentRuntimeEvent();
            artifactEvent.setEventId("artifact-created-" + created.getArtifact().getId());
            artifactEvent.setRunId(runId);
            artifactEvent.setType(AgentRuntimeEventTypeEnum.ARTIFACT_CREATED);
            artifactEvent.setContent(created.getArtifact().getTitle());
            artifactEvent.setPayload(Map.of(
                    "artifactId", created.getArtifact().getId(),
                    "artifactType", created.getArtifact().getType().name(),
                    "version", created.getArtifact().getCurrentVersion()));
            artifactEvent.setOccurredAt(new Date());
            persistEvent(artifactEvent);
            for (AgentArtifactDetail extracted : artifactService.extractStructuredArtifacts(
                    task.getId(), runId, task.getCreatedBy(), markdown)) {
                AgentRuntimeEvent extractedEvent = new AgentRuntimeEvent();
                extractedEvent.setEventId("artifact-created-" + extracted.getArtifact().getId());
                extractedEvent.setRunId(runId);
                extractedEvent.setType(AgentRuntimeEventTypeEnum.ARTIFACT_CREATED);
                extractedEvent.setContent(extracted.getArtifact().getTitle());
                extractedEvent.setPayload(Map.of(
                        "artifactId", extracted.getArtifact().getId(),
                        "artifactType", extracted.getArtifact().getType().name(),
                        "version", extracted.getArtifact().getCurrentVersion()));
                extractedEvent.setOccurredAt(new Date());
                persistEvent(extractedEvent);
            }
        }
        AgentTask refreshed = taskService.get(task.getId());
        if (artifactService.satisfiesOutputContract(agent, task.getId())) {
            transitionTaskIf(refreshed.getStatus(), refreshed,
                    AgentTaskStatusEnum.IN_PROGRESS, AgentTaskStatusEnum.IN_REVIEW);
        }
    }

    private boolean containsPseudoToolCall(String runId) {
        String streamedText = storage.listRunEvents(runId).stream()
                .filter(event -> event.getType() == AgentRuntimeEventTypeEnum.MESSAGE_DELTA)
                .map(AgentRunEvent::getContent)
                .filter(StringUtils::isNotEmpty)
                .reduce("", String::concat);
        return StringUtils.containsIgnoreCase(streamedText, DSML_TOOL_CALL_MARKER)
                || StringUtils.containsIgnoreCase(streamedText, XML_TOOL_CALL_MARKER);
    }

    private void failRun(String runId, String failureReason) {
        AgentRun current = runService.get(runId);
        if (terminal(current.getStatus())) {
            return;
        }
        String resolvedReason = StringUtils.defaultIfBlank(failureReason, "Agent runtime failed");
        transitionRun(current, AgentRunStatusEnum.FAILED, resolvedReason, null);
    }

    private AgentRun transitionRun(AgentRun current, AgentRunStatusEnum target, String failureReason,
                                   String resultSummary) {
        AgentRunTransitionRequest transition = new AgentRunTransitionRequest();
        transition.setRunId(current.getId());
        transition.setExpectedRevision(current.getRevision());
        transition.setTargetStatus(target);
        transition.setFailureReason(failureReason);
        transition.setResultSummary(resultSummary);
        return runService.transition(transition);
    }

    private String completedAnswerSummary(String runId) {
        String answer = completedAnswer(runId);
        if (answer.length() <= MAX_RESULT_SUMMARY_LENGTH) {
            return answer;
        }
        return answer.substring(0, MAX_RESULT_SUMMARY_LENGTH) + "\n[truncated]";
    }

    private String completedAnswer(String runId) {
        String answer = storage.listRunEvents(runId).stream()
                .filter(event -> event.getType() == AgentRuntimeEventTypeEnum.MESSAGE_DELTA)
                .map(AgentRunEvent::getContent)
                .filter(StringUtils::isNotEmpty)
                .reduce("", String::concat);
        return answer;
    }

    private void transitionTaskIf(AgentTaskStatusEnum currentStatus, AgentTask task,
                                  AgentTaskStatusEnum expected, AgentTaskStatusEnum target) {
        if (currentStatus != expected) {
            return;
        }
        AgentTaskTransitionRequest transition = new AgentTaskTransitionRequest();
        transition.setTaskId(task.getId());
        transition.setExpectedRevision(task.getRevision());
        transition.setTargetStatus(target);
        taskService.transition(transition);
    }

    private void persistStatus(String runId, String status) {
        AgentRuntimeEvent event = new AgentRuntimeEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setRunId(runId);
        event.setType(AgentRuntimeEventTypeEnum.STATUS);
        event.setContent(status);
        event.setPayload(Map.of("status", status));
        event.setOccurredAt(new Date());
        persistEvent(event);
    }

    private AgentRunEvent persistEvent(AgentRuntimeEvent source) {
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(StringUtils.defaultIfBlank(source.getEventId(), UUID.randomUUID().toString()));
        event.setRunId(source.getRunId());
        event.setType(source.getType());
        event.setContent(source.getContent());
        event.setPayload(source.getPayload() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getPayload()));
        event.setOccurredAt(source.getOccurredAt() == null ? new Date() : source.getOccurredAt());
        event.setPersistedAt(new Date());
        return storage.appendRunEvent(event);
    }

    private AgentRunStatusEnum runtimeStatus(AgentRuntimeEvent event) {
        Object payloadStatus = event.getPayload() == null ? null : event.getPayload().get("status");
        String value = payloadStatus == null ? event.getContent() : String.valueOf(payloadStatus);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return AgentRunStatusEnum.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void validateRuntimeEvent(AgentRuntimeEvent event) {
        if (event == null || StringUtils.isBlank(event.getRunId()) || event.getType() == null) {
            throw new IllegalArgumentException("runtime event run id and type are required");
        }
        runService.get(event.getRunId());
    }

    private boolean terminal(AgentRunStatusEnum status) {
        return status == AgentRunStatusEnum.COMPLETED || status == AgentRunStatusEnum.FAILED
                || status == AgentRunStatusEnum.CANCELLED || status == AgentRunStatusEnum.UNKNOWN;
    }
}

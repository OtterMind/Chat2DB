package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRunHandle;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCapabilities;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeResumeRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskContextCreateRequest;
import ai.chat2db.community.domain.api.service.agent.runtime.AgentEventSink;
import ai.chat2db.community.domain.api.service.agent.runtime.AgentRuntime;
import ai.chat2db.community.domain.core.impl.agent.runtime.AgentRunCoordinatorImpl;
import ai.chat2db.community.domain.core.impl.agent.runtime.AgentRuntimeRegistry;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunCoordinatorTest {

    @Test
    void dispatchesRunPersistsDeduplicatedEventsAndMovesTaskToReview() {
        Fixture fixture = fixture(new CompletingRuntime());

        AgentRunCoordinatorImpl coordinator = fixture.coordinator();
        coordinator.dispatch(fixture.creation().getInitialRun().getId());

        assertEquals(AgentRunStatusEnum.COMPLETED,
                fixture.runService().get(fixture.creation().getInitialRun().getId()).getStatus());
        assertEquals("analysis complete",
                fixture.runService().get(fixture.creation().getInitialRun().getId()).getResultSummary());
        assertEquals(AgentTaskStatusEnum.IN_REVIEW,
                fixture.taskService().get(fixture.creation().getTask().getId()).getStatus());
        assertEquals(1, fixture.artifactService().listByTask(fixture.creation().getTask().getId()).size());
        assertEquals(AgentArtifactTypeEnum.REPORT,
                fixture.artifactService().listByTask(fixture.creation().getTask().getId()).get(0).getType());
        assertEquals(List.of("DISPATCHED", "RUNNING", "analysis complete", "COMPLETED",
                        "Analyze refunds - Analysis Report"),
                coordinator.listEvents(fixture.creation().getInitialRun().getId()).stream()
                        .map(event -> event.getContent()).toList());
        assertTrue(coordinator.listEvents(fixture.creation().getInitialRun().getId()).stream()
                .allMatch(event -> event.getSequence() != null));
    }

    @Test
    void recordsSynchronousRuntimeFailureAndKeepsTaskRecoverable() {
        Fixture fixture = fixture(new FailingRuntime());

        fixture.coordinator().dispatch(fixture.creation().getInitialRun().getId());

        assertEquals(AgentRunStatusEnum.FAILED,
                fixture.runService().get(fixture.creation().getInitialRun().getId()).getStatus());
        assertEquals(AgentTaskStatusEnum.IN_PROGRESS,
                fixture.taskService().get(fixture.creation().getTask().getId()).getStatus());
        assertEquals(AgentRuntimeEventTypeEnum.ERROR,
                fixture.coordinator().listEvents(fixture.creation().getInitialRun().getId()).get(2).getType());
    }

    @Test
    void rejectsPseudoToolCallTextInsteadOfPublishingItAsAnAnalysisReport() {
        Fixture fixture = fixture(new PseudoToolCallRuntime());

        fixture.coordinator().dispatch(fixture.creation().getInitialRun().getId());

        assertEquals(AgentRunStatusEnum.FAILED,
                fixture.runService().get(fixture.creation().getInitialRun().getId()).getStatus());
        assertTrue(fixture.artifactService().listByTask(fixture.creation().getTask().getId()).isEmpty());
        assertTrue(fixture.coordinator().listEvents(fixture.creation().getInitialRun().getId()).stream()
                .anyMatch(event -> event.getType() == AgentRuntimeEventTypeEnum.ERROR
                        && event.getContent().contains("native tool-calling support")));
    }

    @Test
    void resumesApprovedRunWithFreshControlPlaneSnapshot() {
        ResumableRuntime runtime = new ResumableRuntime();
        Fixture fixture = fixture(runtime);
        String runId = fixture.creation().getInitialRun().getId();
        fixture.coordinator().dispatch(runId);

        fixture.coordinator().resumeAfterApproval(runId, "Execute approved SQL hash abc123");

        assertTrue(runtime.resumedContext.get().contains("Execute approved SQL hash abc123"));
        assertEquals(AgentRunStatusEnum.COMPLETED, fixture.runService().get(runId).getStatus());
    }

    @Test
    void includesPreviousAnswerAndToolResultInTheNextRunContext() {
        ContextCapturingRuntime runtime = new ContextCapturingRuntime();
        Fixture fixture = fixture(runtime);
        String taskId = fixture.creation().getTask().getId();

        fixture.coordinator().dispatch(fixture.creation().getInitialRun().getId());
        AgentTaskContextCreateRequest message = new AgentTaskContextCreateRequest();
        message.setTaskId(taskId);
        message.setType(ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum.COMMENT);
        message.setContent("Use the previous result and explain the reporting database.");
        new AgentTaskContextServiceImpl(fixture.storage(), fixture.taskService()).append(message);
        AgentTaskCreation followUp = fixture.taskService().createRun(
                taskId, ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum.USER_MESSAGE);
        fixture.coordinator().dispatch(followUp.getInitialRun().getId());

        String context = runtime.secondRunContext.get();
        assertTrue(context.contains("Previous Execution History"));
        assertTrue(context.contains("Existing databases: sales, reporting"));
        assertTrue(context.contains("TOOL_RESULT list_all_databases: sales, reporting"));
        assertTrue(context.contains("Do not repeat a database query"));
        assertEquals("Use the previous result and explain the reporting database.", runtime.secondRunInput.get());
    }

    @Test
    void leavesExternalRunQueuedUntilDaemonClaimsIt() {
        Fixture fixture = fixture(new CompletingRuntime());
        ai.chat2db.community.domain.api.model.agent.AgentRun run = fixture.creation().getInitialRun();
        run.setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);

        fixture.coordinator().dispatch(run.getId());

        assertEquals(AgentRunStatusEnum.QUEUED, fixture.runService().get(run.getId()).getStatus());
        assertEquals(AgentTaskStatusEnum.TODO,
                fixture.taskService().get(fixture.creation().getTask().getId()).getStatus());
        assertTrue(fixture.coordinator().listEvents(run.getId()).isEmpty());
    }

    private Fixture fixture(AgentRuntime runtime) {
        AgentControlServiceTest.MemoryAgentControlStorage storage =
                new AgentControlServiceTest.MemoryAgentControlStorage();
        AgentDefinitionServiceImpl agentService = new AgentDefinitionServiceImpl(storage);
        AgentTaskServiceImpl taskService = new AgentTaskServiceImpl(storage);
        AgentRunServiceImpl runService = new AgentRunServiceImpl(storage);

        AgentDefinitionCreateRequest agentRequest = new AgentDefinitionCreateRequest();
        agentRequest.setName("Data Analyst");
        agentRequest.setCapabilities(new LinkedHashSet<>(List.of(AgentCapabilityEnum.DATA_READ)));
        AgentDataScope scope = new AgentDataScope();
        scope.setDataSourceId(7L);
        scope.setDatabaseName("sales");
        agentRequest.setDataScopes(List.of(scope));
        AgentDefinition agent = agentService.create(agentRequest);

        AgentTaskCreateRequest taskRequest = new AgentTaskCreateRequest();
        taskRequest.setTitle("Analyze refunds");
        taskRequest.setAssigneeAgentId(agent.getId());
        taskRequest.setDataScopeSnapshot(List.of(scope));
        AgentTaskCreation creation = taskService.create(taskRequest);

        AgentArtifactServiceImpl artifactService = new AgentArtifactServiceImpl(storage);
        AgentRunCoordinatorImpl coordinator = new AgentRunCoordinatorImpl(runService, taskService, agentService,
                new AgentContextAssemblerImpl(storage), artifactService, storage,
                new AgentRuntimeRegistry(List.of(runtime)), null);
        return new Fixture(coordinator, taskService, runService, artifactService, storage, creation);
    }

    private record Fixture(AgentRunCoordinatorImpl coordinator, AgentTaskServiceImpl taskService,
                           AgentRunServiceImpl runService, AgentArtifactServiceImpl artifactService,
                           AgentControlServiceTest.MemoryAgentControlStorage storage,
                           AgentTaskCreation creation) {
    }

    private static class CompletingRuntime implements AgentRuntime {
        @Override
        public AgentRuntimeTypeEnum type() {
            return AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI;
        }

        @Override
        public AgentRuntimeCapabilities capabilities() {
            return new AgentRuntimeCapabilities();
        }

        @Override
        public AgentRunHandle start(AgentRuntimeStartRequest request, AgentEventSink eventSink) {
            AgentRuntimeEvent message = event(request, "event-message", AgentRuntimeEventTypeEnum.MESSAGE_DELTA,
                    "analysis complete", Map.of());
            eventSink.emit(message);
            eventSink.emit(message);
            eventSink.emit(event(request, "event-complete", AgentRuntimeEventTypeEnum.STATUS,
                    "COMPLETED", Map.of("status", "COMPLETED")));
            return new AgentRunHandle();
        }

        @Override
        public void resume(AgentRuntimeResumeRequest request, AgentEventSink eventSink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(String runId) {
        }

        protected AgentRuntimeEvent event(AgentRuntimeStartRequest request, String id,
                                        AgentRuntimeEventTypeEnum type, String content, Map<String, Object> payload) {
            AgentRuntimeEvent event = new AgentRuntimeEvent();
            event.setEventId(id);
            event.setRunId(request.getRun().getId());
            event.setType(type);
            event.setContent(content);
            event.setPayload(payload);
            event.setOccurredAt(new Date());
            return event;
        }
    }

    private static final class FailingRuntime extends CompletingRuntime {
        @Override
        public AgentRunHandle start(AgentRuntimeStartRequest request, AgentEventSink eventSink) {
            throw new IllegalStateException("provider unavailable");
        }
    }

    private static final class PseudoToolCallRuntime extends CompletingRuntime {
        @Override
        public AgentRunHandle start(AgentRuntimeStartRequest request, AgentEventSink eventSink) {
            eventSink.emit(event(request, "pseudo-message", AgentRuntimeEventTypeEnum.MESSAGE_DELTA,
                    "Inspecting datasource. <｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke "
                            + "name=\"list_all_datasources\"></｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>", Map.of()));
            eventSink.emit(event(request, "pseudo-complete", AgentRuntimeEventTypeEnum.STATUS,
                    "COMPLETED", Map.of("status", "COMPLETED")));
            return new AgentRunHandle();
        }
    }

    private static final class ResumableRuntime implements AgentRuntime {
        private final AtomicReference<String> resumedContext = new AtomicReference<>();

        @Override
        public AgentRuntimeTypeEnum type() {
            return AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI;
        }

        @Override
        public AgentRuntimeCapabilities capabilities() {
            AgentRuntimeCapabilities capabilities = new AgentRuntimeCapabilities();
            capabilities.setApprovalResume(true);
            return capabilities;
        }

        @Override
        public AgentRunHandle start(AgentRuntimeStartRequest request, AgentEventSink eventSink) {
            return new AgentRunHandle();
        }

        @Override
        public void resume(AgentRuntimeResumeRequest request, AgentEventSink eventSink) {
            resumedContext.set(request.getStartRequest().getAssembledContext());
            AgentRuntimeEvent message = new AgentRuntimeEvent();
            message.setEventId("resumed-message");
            message.setRunId(request.getRunId());
            message.setType(AgentRuntimeEventTypeEnum.MESSAGE_DELTA);
            message.setContent("approved action complete");
            message.setOccurredAt(new Date());
            eventSink.emit(message);
            AgentRuntimeEvent completed = new AgentRuntimeEvent();
            completed.setEventId("resumed-complete");
            completed.setRunId(request.getRunId());
            completed.setType(AgentRuntimeEventTypeEnum.STATUS);
            completed.setContent("COMPLETED");
            completed.setPayload(Map.of("status", "COMPLETED"));
            completed.setOccurredAt(new Date());
            eventSink.emit(completed);
        }

        @Override
        public void cancel(String runId) {
        }
    }

    private static final class ContextCapturingRuntime extends CompletingRuntime {
        private int starts;
        private final AtomicReference<String> secondRunContext = new AtomicReference<>();
        private final AtomicReference<String> secondRunInput = new AtomicReference<>();

        @Override
        public AgentRunHandle start(AgentRuntimeStartRequest request, AgentEventSink eventSink) {
            starts++;
            if (starts == 1) {
                eventSink.emit(event(request, "tool-call", AgentRuntimeEventTypeEnum.TOOL_CALL,
                        "list_all_databases", Map.of("name", "list_all_databases")));
                eventSink.emit(event(request, "tool-result", AgentRuntimeEventTypeEnum.TOOL_RESULT,
                        "sales, reporting", Map.of("name", "list_all_databases")));
                eventSink.emit(event(request, "first-answer", AgentRuntimeEventTypeEnum.MESSAGE_DELTA,
                        "Existing databases: sales, reporting", Map.of()));
            } else {
                secondRunContext.set(request.getAssembledContext());
                secondRunInput.set(request.getCurrentInput());
                eventSink.emit(event(request, "second-answer", AgentRuntimeEventTypeEnum.MESSAGE_DELTA,
                        "Reused the previous database list.", Map.of()));
            }
            eventSink.emit(event(request, "completed-" + starts, AgentRuntimeEventTypeEnum.STATUS,
                    "COMPLETED", Map.of("status", "COMPLETED")));
            return new AgentRunHandle();
        }
    }
}

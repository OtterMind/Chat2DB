package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecisionEnum;
import ai.chat2db.community.domain.api.model.request.agent.AgentApprovalDecisionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskMessageRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskContextCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskLifecycleRequest;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum;
import ai.chat2db.community.domain.api.service.agent.IAgentDefinitionService;
import ai.chat2db.community.domain.api.service.agent.IAgentRunCoordinator;
import ai.chat2db.community.domain.api.service.agent.IAgentRunService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskService;
import ai.chat2db.community.domain.api.service.agent.IAgentArtifactService;
import ai.chat2db.community.domain.api.service.agent.IAgentToolGateway;
import ai.chat2db.community.domain.api.service.agent.IAgentArtifactPublicationService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskContextService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentControlControllerTest {

    @Test
    void bindsCurrentIdentityAndFiltersDefinitionAndTaskLists() {
        AtomicReference<AgentDefinitionCreateRequest> captured = new AtomicReference<>();
        AgentDefinition mine = agent("mine", 7L);
        AgentDefinition system = agent("system", null);
        AgentDefinition other = agent("other", 8L);
        IAgentDefinitionService agentService = proxy(IAgentDefinitionService.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "create" -> {
                    captured.set((AgentDefinitionCreateRequest) args[0]);
                    yield mine;
                }
                case "list" -> List.of(mine, system, other);
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        AgentTask myTask = task("mine-task", 7L);
        AgentTask otherTask = task("other-task", 8L);
        IAgentTaskService taskService = proxy(IAgentTaskService.class, (proxy, method, args) -> {
            if ("list".equals(method.getName())) {
                return List.of(myTask, otherTask);
            }
            throw new UnsupportedOperationException(method.getName());
        });
        AgentControlController controller = controller(agentService, taskService);

        AgentDefinitionCreateRequest request = new AgentDefinitionCreateRequest();
        request.setName("Analyst");
        request.setCreatedBy(999L);
        controller.createAgent(request);

        assertEquals(7L, captured.get().getCreatedBy());
        assertEquals(List.of("mine", "system"), controller.listAgents().getData().stream()
                .map(AgentDefinition::getId).toList());
        assertEquals(List.of("mine-task"), controller.listTasks().getData().stream()
                .map(AgentTask::getId).toList());
    }

    @Test
    void rejectsTaskOwnedByAnotherUser() {
        IAgentDefinitionService agentService = proxy(IAgentDefinitionService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        IAgentTaskService taskService = proxy(IAgentTaskService.class, (proxy, method, args) -> {
            if ("get".equals(method.getName())) {
                return task("other-task", 8L);
            }
            throw new UnsupportedOperationException(method.getName());
        });
        AgentControlController controller = controller(agentService, taskService);

        assertThrows(IllegalArgumentException.class, () -> controller.getTask("other-task"));
    }

    @Test
    void listsOwnedArchiveAndBindsRevisionForArchiveAndPermanentDelete() {
        AtomicReference<Long> archiveRevision = new AtomicReference<>();
        AtomicReference<Long> deleteRevision = new AtomicReference<>();
        AgentTask mine = task("mine-task", 7L);
        AgentTask other = task("other-task", 8L);
        IAgentDefinitionService agentService = proxy(IAgentDefinitionService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        IAgentTaskService taskService = proxy(IAgentTaskService.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "listArchived" -> List.of(mine, other);
                case "get" -> mine;
                case "archive" -> {
                    archiveRevision.set((Long) args[1]);
                    yield mine;
                }
                case "deleteArchived" -> {
                    deleteRevision.set((Long) args[1]);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        AgentControlController controller = controller(agentService, taskService);
        AgentTaskLifecycleRequest request = new AgentTaskLifecycleRequest();
        request.setExpectedRevision(3L);

        assertEquals(List.of("mine-task"), controller.listArchivedTasks().getData().stream()
                .map(AgentTask::getId).toList());
        controller.archiveTask("mine-task", request);
        controller.deleteArchivedTask("mine-task", request);

        assertEquals(3L, archiveRevision.get());
        assertEquals(3L, deleteRevision.get());
    }

    @Test
    void approvalDecisionUsesCurrentIdentityAfterTaskOwnershipCheck() {
        AtomicReference<AgentApprovalDecisionRequest> captured = new AtomicReference<>();
        AtomicReference<String> resumedContext = new AtomicReference<>();
        AgentApproval pending = new AgentApproval();
        pending.setId("approval-1");
        pending.setRunId("run-1");
        pending.setProposalId("proposal-1");
        pending.setRevision(1L);
        pending.setDecision(AgentApprovalDecisionEnum.APPROVE);
        AgentSqlProposal proposal = new AgentSqlProposal();
        proposal.setId("proposal-1");
        proposal.setProposalVersion(1);
        proposal.setSqlHash("hash-1");
        proposal.setDataSourceId(10L);
        proposal.setSqlSnapshot("UPDATE orders SET status = 'DONE' WHERE id = 1");
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setTaskId("task-1");

        IAgentDefinitionService agentService = proxy(IAgentDefinitionService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        IAgentTaskService taskService = proxy(IAgentTaskService.class, (proxy, method, args) -> {
            if ("get".equals(method.getName())) {
                return task("task-1", 7L);
            }
            throw new UnsupportedOperationException(method.getName());
        });
        IAgentRunService runService = proxy(IAgentRunService.class, (proxy, method, args) -> {
            if ("get".equals(method.getName())) {
                return run;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        IAgentToolGateway toolGateway = proxy(IAgentToolGateway.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getApproval" -> pending;
                case "decide" -> {
                    captured.set((AgentApprovalDecisionRequest) args[0]);
                    yield pending;
                }
                case "listProposals" -> List.of(proposal);
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        IAgentRunCoordinator coordinator = proxy(IAgentRunCoordinator.class, (proxy, method, args) -> {
            if ("resumeAfterApproval".equals(method.getName())) {
                resumedContext.set((String) args[1]);
                return run;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        AgentControlController controller = controller(agentService, taskService, runService, coordinator, toolGateway);
        AgentApprovalDecisionRequest request = new AgentApprovalDecisionRequest();
        request.setApprovalId("untrusted-id");
        request.setExpectedRevision(1L);
        request.setDecision(AgentApprovalDecisionEnum.APPROVE);
        request.setDecidedBy(999L);

        controller.decideApproval("approval-1", request);

        assertEquals("approval-1", captured.get().getApprovalId());
        assertEquals(7L, captured.get().getDecidedBy());
        assertTrue(resumedContext.get().contains("UPDATE orders"));
    }

    @Test
    void continuingTaskAppendsOwnedCommentAndDispatchesUserMessageRun() {
        AtomicReference<AgentTaskContextCreateRequest> capturedContext = new AtomicReference<>();
        AtomicReference<AgentRunTriggerTypeEnum> capturedTrigger = new AtomicReference<>();
        AtomicReference<String> dispatchedRunId = new AtomicReference<>();
        AgentTask ownedTask = task("task-1", 7L);
        AgentRun followUpRun = new AgentRun();
        followUpRun.setId("run-2");
        followUpRun.setTaskId("task-1");

        IAgentDefinitionService agentService = proxy(IAgentDefinitionService.class,
                (proxy, method, args) -> List.of());
        IAgentTaskService taskService = proxy(IAgentTaskService.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "get" -> ownedTask;
                case "createRun" -> {
                    capturedTrigger.set((AgentRunTriggerTypeEnum) args[1]);
                    yield new AgentTaskCreation(ownedTask, followUpRun);
                }
                case "listRuns" -> List.of(followUpRun);
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        IAgentRunService runService = proxy(IAgentRunService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        IAgentRunCoordinator coordinator = proxy(IAgentRunCoordinator.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "dispatch" -> {
                    dispatchedRunId.set((String) args[0]);
                    yield followUpRun;
                }
                case "listEvents" -> List.of();
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        IAgentToolGateway toolGateway = proxy(IAgentToolGateway.class, (proxy, method, args) -> List.of());
        IAgentArtifactService artifactService = proxy(IAgentArtifactService.class, (proxy, method, args) -> List.of());
        IAgentArtifactPublicationService publicationService = proxy(
                IAgentArtifactPublicationService.class, (proxy, method, args) -> List.of());
        IAgentTaskContextService contextService = proxy(IAgentTaskContextService.class, (proxy, method, args) -> {
            if ("append".equals(method.getName())) {
                capturedContext.set((AgentTaskContextCreateRequest) args[0]);
                return null;
            }
            if ("list".equals(method.getName())) return List.of();
            throw new UnsupportedOperationException(method.getName());
        });
        AgentControlController controller = new AgentControlController(agentService, taskService, runService,
                coordinator, artifactService, toolGateway, publicationService, contextService, () -> 7L);
        AgentTaskMessageRequest request = new AgentTaskMessageRequest();
        request.setContent("Compare the result with last month.");

        controller.continueTask("task-1", request);

        assertEquals("Compare the result with last month.", capturedContext.get().getContent());
        assertEquals(7L, capturedContext.get().getCreatedBy());
        assertEquals(AgentRunTriggerTypeEnum.USER_MESSAGE, capturedTrigger.get());
        assertEquals("run-2", dispatchedRunId.get());
    }

    private AgentControlController controller(IAgentDefinitionService agentService, IAgentTaskService taskService) {
        IAgentRunService runService = proxy(IAgentRunService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        IAgentToolGateway toolGateway = proxy(IAgentToolGateway.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        return controller(agentService, taskService, runService, toolGateway);
    }

    private AgentControlController controller(IAgentDefinitionService agentService, IAgentTaskService taskService,
                                              IAgentRunService runService, IAgentToolGateway toolGateway) {
        IAgentRunCoordinator coordinator = proxy(IAgentRunCoordinator.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        return controller(agentService, taskService, runService, coordinator, toolGateway);
    }

    private AgentControlController controller(IAgentDefinitionService agentService, IAgentTaskService taskService,
                                              IAgentRunService runService, IAgentRunCoordinator coordinator,
                                              IAgentToolGateway toolGateway) {
        IAgentArtifactService artifactService = proxy(IAgentArtifactService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        IAgentArtifactPublicationService publicationService = proxy(IAgentArtifactPublicationService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        IAgentTaskContextService contextService = proxy(IAgentTaskContextService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        return new AgentControlController(agentService, taskService, runService, coordinator,
                artifactService, toolGateway, publicationService, contextService, () -> 7L);
    }

    private AgentDefinition agent(String id, Long createdBy) {
        AgentDefinition agent = new AgentDefinition();
        agent.setId(id);
        agent.setCreatedBy(createdBy);
        return agent;
    }

    private AgentTask task(String id, Long createdBy) {
        AgentTask task = new AgentTask();
        task.setId(id);
        task.setCreatedBy(createdBy);
        return task;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, handler);
    }
}

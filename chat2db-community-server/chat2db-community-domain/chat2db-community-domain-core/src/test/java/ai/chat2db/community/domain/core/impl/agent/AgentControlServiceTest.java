package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalModeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTransportEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDataWikiBinding;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDetail;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactEvidence;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactVersion;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentToolAttempt;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDashboardRef;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.agent.AgentTaskContext;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionUpdateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunTransitionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskTransitionRequest;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDefinition;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiResource;
import ai.chat2db.community.domain.api.service.datawiki.IDataWikiService;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentRuntimeControlStorage;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentControlServiceTest {

    private MemoryAgentControlStorage storage;
    private AgentDefinitionServiceImpl agentService;
    private AgentTaskServiceImpl taskService;
    private AgentRunServiceImpl runService;

    @BeforeEach
    void setUp() {
        storage = new MemoryAgentControlStorage();
        agentService = new AgentDefinitionServiceImpl(storage);
        taskService = new AgentTaskServiceImpl(storage);
        runService = new AgentRunServiceImpl(storage);
    }

    @Test
    void createsEmbeddedAgentWithNormalizedAccessPolicy() {
        AgentDefinitionCreateRequest request = new AgentDefinitionCreateRequest();
        request.setName("  Data Analyst  ");
        request.setCapabilities(new LinkedHashSet<>(List.of(
                AgentCapabilityEnum.METADATA_READ,
                AgentCapabilityEnum.DATA_READ)));
        AgentDataScope scope = scope(7L, "sales", "public", List.of("orders"));
        scope.setMaxRows(null);
        scope.setTimeoutSeconds(null);
        request.setDataScopes(List.of(scope));
        request.setCreatedBy(1L);

        AgentDefinition created = agentService.create(request);

        assertNotNull(created.getId());
        assertEquals("Data Analyst", created.getName());
        assertEquals(AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI, created.getRuntimeType());
        assertEquals(200, created.getDataScopes().get(0).getMaxRows());
        assertEquals(60, created.getDataScopes().get(0).getTimeoutSeconds());
        assertEquals(1L, created.getRevision());
    }

    @Test
    void persistsDataWikiPolicyAndAppliesItToTaskScope() {
        DataWikiResource resource = new DataWikiResource();
        resource.setDataSourceId(7L);
        resource.setDatabaseName("sales");
        resource.setSchemaName("public");
        resource.setTableName("orders");
        DataWikiDefinition wiki = new DataWikiDefinition();
        wiki.setId("wiki-1");
        wiki.setCreatedBy(1L);
        wiki.setResources(List.of(resource));
        IDataWikiService dataWikiService = (IDataWikiService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDataWikiService.class},
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) return wiki;
                    throw new UnsupportedOperationException(method.getName());
                });
        AgentDefinitionServiceImpl wikiAgentService = new AgentDefinitionServiceImpl(
                storage, null, dataWikiService);
        AgentDataWikiBinding binding = new AgentDataWikiBinding();
        binding.setDataWikiId(wiki.getId());
        binding.setMaxRows(81);
        binding.setTimeoutSeconds(17);
        binding.setApprovalMode(AgentApprovalModeEnum.ALWAYS);

        AgentDefinitionCreateRequest agentRequest = new AgentDefinitionCreateRequest();
        agentRequest.setName("Wiki analyst");
        agentRequest.setCreatedBy(1L);
        agentRequest.setDataWikiBindings(List.of(binding));
        AgentDefinition created = wikiAgentService.create(agentRequest);

        assertEquals(List.of("wiki-1"), created.getDataWikiIds());
        assertEquals(81, created.getDataWikiBindings().get(0).getMaxRows());
        assertEquals(List.of("orders"), created.getEffectiveDataScopes().get(0).getTableNames());

        AgentTaskServiceImpl wikiTaskService = new AgentTaskServiceImpl(storage, null, wikiAgentService);
        AgentDataScope requested = scope(7L, "sales", "public", List.of("orders"));
        requested.setMaxRows(500);
        requested.setTimeoutSeconds(60);
        requested.setApprovalMode(AgentApprovalModeEnum.NEVER);
        requested.setAllowProduction(true);
        AgentTaskCreateRequest taskRequest = new AgentTaskCreateRequest();
        taskRequest.setTitle("Read Wiki table");
        taskRequest.setAssigneeAgentId(created.getId());
        taskRequest.setDataScopeSnapshot(List.of(requested));

        AgentTask task = wikiTaskService.create(taskRequest).getTask();
        assertEquals(81, task.getDataScopeSnapshot().get(0).getMaxRows());
        assertEquals(17, task.getDataScopeSnapshot().get(0).getTimeoutSeconds());
        assertEquals(AgentApprovalModeEnum.ALWAYS, task.getDataScopeSnapshot().get(0).getApprovalMode());
        assertEquals(false, task.getDataScopeSnapshot().get(0).getAllowProduction());
    }

    @Test
    void updatesAndArchivesAgentWithOptimisticRevision() {
        AgentDefinitionCreateRequest createRequest = new AgentDefinitionCreateRequest();
        createRequest.setName("Analyst");
        AgentDefinition created = agentService.create(createRequest);

        AgentDefinitionUpdateRequest updateRequest = new AgentDefinitionUpdateRequest();
        updateRequest.setAgentId(created.getId());
        updateRequest.setExpectedRevision(created.getRevision());
        updateRequest.setName("Senior Analyst");
        updateRequest.setAvatar("data:image/webp;base64,avatar");
        updateRequest.setCapabilities(new LinkedHashSet<>(List.of(AgentCapabilityEnum.DATA_READ)));
        AgentDefinition updated = agentService.update(updateRequest);

        assertEquals("Senior Analyst", updated.getName());
        assertEquals("data:image/webp;base64,avatar", updated.getAvatar());
        assertEquals(2L, updated.getRevision());
        assertThrows(ConcurrentModificationException.class, () -> agentService.update(updateRequest));

        AgentDefinition archived = agentService.archive(updated.getId(), updated.getRevision());
        assertEquals(ai.chat2db.community.domain.api.enums.agent.AgentStatusEnum.ARCHIVED, archived.getStatus());
        assertEquals(3L, archived.getRevision());
    }

    @Test
    void appendsImmutableTaskContextAndIncludesItInTheNextRunContext() {
        AgentDefinitionCreateRequest agentRequest = new AgentDefinitionCreateRequest();
        agentRequest.setName("Analyst");
        AgentDefinition agent = agentService.create(agentRequest);
        AgentTaskCreateRequest taskRequest = new AgentTaskCreateRequest();
        taskRequest.setTitle("Analyze refunds");
        taskRequest.setDescription("Find the refund rate trend");
        taskRequest.setAssigneeAgentId(agent.getId());
        AgentTaskCreation creation = taskService.create(taskRequest);

        AgentTaskContextServiceImpl contextService = new AgentTaskContextServiceImpl(storage, taskService);
        ai.chat2db.community.domain.api.model.request.agent.AgentTaskContextCreateRequest contextRequest =
                new ai.chat2db.community.domain.api.model.request.agent.AgentTaskContextCreateRequest();
        contextRequest.setTaskId(creation.getTask().getId());
        contextRequest.setType(ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum.PINNED);
        contextRequest.setTitle("Metric definition");
        contextRequest.setContent("Exclude test orders.");
        contextService.append(contextRequest);

        String assembled = new AgentContextAssemblerImpl(storage).assemble(
                agent, creation.getTask(), List.of(creation.getInitialRun()));

        assertTrue(assembled.contains("Find the refund rate trend"));
        assertTrue(assembled.contains("Pinned Context"));
        assertTrue(assembled.contains("Exclude test orders."));
    }

    @Test
    void externalAgentRequiresRuntimeProfile() {
        AgentDefinitionCreateRequest request = new AgentDefinitionCreateRequest();
        request.setName("External analyst");
        request.setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);

        assertThrows(IllegalArgumentException.class, () -> agentService.create(request));
    }

    @Test
    void snapshotsExternalRuntimeProfileAndProviderIntoRun() {
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setId("profile-1");
        profile.setName("Codex local");
        profile.setTransport(AgentRuntimeTransportEnum.EXTERNAL_DAEMON);
        profile.setProvider(AgentRuntimeProviderEnum.CODEX);
        profile.setEnabled(true);
        profile.setCreatedBy(7L);
        profile.setRevision(1L);
        IAgentRuntimeControlStorage runtimeStorage = (IAgentRuntimeControlStorage) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IAgentRuntimeControlStorage.class},
                (proxy, method, args) -> {
                    if ("getRuntimeProfile".equals(method.getName())) {
                        return profile;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        AgentDefinitionServiceImpl externalAgentService = new AgentDefinitionServiceImpl(storage, runtimeStorage);
        AgentTaskServiceImpl externalTaskService = new AgentTaskServiceImpl(storage, runtimeStorage);
        AgentDefinitionCreateRequest agentRequest = new AgentDefinitionCreateRequest();
        agentRequest.setName("External analyst");
        agentRequest.setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);
        agentRequest.setRuntimeProfileId(profile.getId());
        agentRequest.setCreatedBy(7L);
        AgentDefinition externalAgent = externalAgentService.create(agentRequest);
        AgentTaskCreateRequest taskRequest = new AgentTaskCreateRequest();
        taskRequest.setTitle("Analyze refunds with Codex");
        taskRequest.setAssigneeAgentId(externalAgent.getId());
        taskRequest.setCreatedBy(7L);

        AgentTaskCreation creation = externalTaskService.create(taskRequest);

        AgentRun initialRun = creation.getInitialRun();
        assertEquals(profile.getId(), initialRun.getRuntimeProfileId());
        assertEquals(AgentRuntimeProviderEnum.CODEX, initialRun.getRuntimeProvider());
        AgentRuntimeProfile snapshot = JSON.parseObject(
                initialRun.getRuntimeProfileSnapshot(), AgentRuntimeProfile.class);
        assertEquals(AgentRuntimeProviderEnum.CODEX, snapshot.getProvider());
        profile.setProvider(AgentRuntimeProviderEnum.HERMES);
        assertEquals(AgentRuntimeProviderEnum.CODEX, JSON.parseObject(
                initialRun.getRuntimeProfileSnapshot(), AgentRuntimeProfile.class).getProvider());
    }

    @Test
    void rejectsInvalidAgentOutputContract() {
        AgentDefinitionCreateRequest request = new AgentDefinitionCreateRequest();
        request.setName("Invalid contract");
        request.setOutputContract("{\"requiredArtifacts\":[{\"type\":\"UNKNOWN\",\"min\":0}]}");

        assertThrows(IllegalArgumentException.class, () -> agentService.create(request));
    }

    @Test
    void createsTaskAndInitialRunAtomicallyWithinAgentScope() {
        AgentDefinition agent = createAgentWithScope(scope(7L, "sales", null, List.of()));
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setTitle("Analyze refund anomalies");
        request.setAssigneeAgentId(agent.getId());
        request.setOriginType(AgentTaskOriginTypeEnum.CHAT);
        request.setOriginSessionId("session-1");
        AgentDataScope requested = scope(7L, "sales", "public", List.of("orders"));
        requested.setMaxRows(500);
        requested.setApprovalMode(AgentApprovalModeEnum.NEVER);
        request.setDataScopeSnapshot(List.of(requested));

        AgentTaskCreation creation = taskService.create(request);

        assertEquals(creation.getInitialRun().getId(), creation.getTask().getCurrentRunId());
        assertEquals(AgentRunStatusEnum.QUEUED, creation.getInitialRun().getStatus());
        assertEquals(1, creation.getInitialRun().getAttempt());
        assertEquals(200, creation.getTask().getDataScopeSnapshot().get(0).getMaxRows());
        assertEquals(AgentApprovalModeEnum.RISK_BASED,
                creation.getTask().getDataScopeSnapshot().get(0).getApprovalMode());
        assertEquals(List.of(creation.getInitialRun()), taskService.listRuns(creation.getTask().getId()));
    }

    @Test
    void rejectsTaskScopeOutsideAgentPolicy() {
        AgentDefinition agent = createAgentWithScope(scope(7L, "sales", "public", List.of("orders")));
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setTitle("Read payments");
        request.setAssigneeAgentId(agent.getId());
        request.setDataScopeSnapshot(List.of(scope(7L, "sales", "public", List.of("payments"))));

        assertThrows(IllegalArgumentException.class, () -> taskService.create(request));
        assertEquals(0, storage.tasks.size());
        assertEquals(0, storage.runs.size());
    }

    @Test
    void transitionsRunWithRevisionAndLifecycleTimestamps() {
        AgentDefinition agent = createAgentWithScope(scope(7L, "sales", null, List.of()));
        AgentTaskCreateRequest taskRequest = new AgentTaskCreateRequest();
        taskRequest.setTitle("Analyze refunds");
        taskRequest.setAssigneeAgentId(agent.getId());
        AgentRun queued = taskService.create(taskRequest).getInitialRun();

        AgentRun running = runService.transition(transition(
                queued.getId(), 1L, AgentRunStatusEnum.RUNNING, null, null));
        AgentRun completed = runService.transition(transition(
                running.getId(), 2L, AgentRunStatusEnum.COMPLETED, null, "Refund analysis complete"));

        assertNotNull(running.getStartedAt());
        assertEquals(2L, running.getRevision());
        assertNotNull(completed.getCompletedAt());
        assertEquals("Refund analysis complete", completed.getResultSummary());
        assertEquals(3L, completed.getRevision());
    }

    @Test
    void rejectsInvalidOrStaleRunTransition() {
        AgentDefinition agent = createAgentWithScope(scope(7L, "sales", null, List.of()));
        AgentTaskCreateRequest taskRequest = new AgentTaskCreateRequest();
        taskRequest.setTitle("Analyze refunds");
        taskRequest.setAssigneeAgentId(agent.getId());
        AgentRun queued = taskService.create(taskRequest).getInitialRun();

        assertThrows(IllegalStateException.class, () -> runService.transition(transition(
                queued.getId(), 1L, AgentRunStatusEnum.COMPLETED, null, "invalid")));
        assertThrows(IllegalStateException.class, () -> runService.transition(transition(
                queued.getId(), 99L, AgentRunStatusEnum.RUNNING, null, null)));
        assertNull(storage.getRun(queued.getId()).getStartedAt());
        assertEquals(AgentRunStatusEnum.QUEUED, storage.getRun(queued.getId()).getStatus());
    }

    @Test
    void transitionsTaskIndependentlyFromRun() {
        AgentDefinition agent = createAgentWithScope(scope(7L, "sales", null, List.of()));
        AgentTaskCreateRequest taskRequest = new AgentTaskCreateRequest();
        taskRequest.setTitle("Analyze refunds");
        taskRequest.setAssigneeAgentId(agent.getId());
        AgentTask task = taskService.create(taskRequest).getTask();

        AgentTaskTransitionRequest start = new AgentTaskTransitionRequest();
        start.setTaskId(task.getId());
        start.setExpectedRevision(1L);
        start.setTargetStatus(ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum.IN_PROGRESS);
        AgentTask inProgress = taskService.transition(start);

        AgentTaskTransitionRequest review = new AgentTaskTransitionRequest();
        review.setTaskId(task.getId());
        review.setExpectedRevision(2L);
        review.setTargetStatus(ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum.IN_REVIEW);
        AgentTask inReview = taskService.transition(review);

        assertEquals(ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum.IN_PROGRESS,
                inProgress.getStatus());
        assertEquals(ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum.IN_REVIEW,
                inReview.getStatus());
        assertEquals(AgentRunStatusEnum.QUEUED, taskService.listRuns(task.getId()).get(0).getStatus());
    }

    @Test
    void createsFollowUpRunAndReopensTaskForUserMessage() {
        AgentDefinition agent = createAgentWithScope(scope(7L, "sales", null, List.of()));
        AgentTaskCreateRequest taskRequest = new AgentTaskCreateRequest();
        taskRequest.setTitle("Analyze refunds");
        taskRequest.setAssigneeAgentId(agent.getId());
        AgentTaskCreation initial = taskService.create(taskRequest);
        AgentRun running = runService.transition(transition(
                initial.getInitialRun().getId(), 1L, AgentRunStatusEnum.RUNNING, null, null));
        runService.transition(transition(running.getId(), 2L, AgentRunStatusEnum.COMPLETED, null, "Done"));

        AgentTaskTransitionRequest start = new AgentTaskTransitionRequest();
        start.setTaskId(initial.getTask().getId());
        start.setExpectedRevision(1L);
        start.setTargetStatus(ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum.IN_PROGRESS);
        taskService.transition(start);
        AgentTaskTransitionRequest review = new AgentTaskTransitionRequest();
        review.setTaskId(initial.getTask().getId());
        review.setExpectedRevision(2L);
        review.setTargetStatus(ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum.IN_REVIEW);
        taskService.transition(review);

        AgentTaskCreation followUp = taskService.createRun(
                initial.getTask().getId(), AgentRunTriggerTypeEnum.USER_MESSAGE);

        assertEquals(2, followUp.getInitialRun().getAttempt());
        assertEquals(initial.getInitialRun().getId(), followUp.getInitialRun().getParentRunId());
        assertEquals(AgentRunTriggerTypeEnum.USER_MESSAGE, followUp.getInitialRun().getTriggerType());
        assertEquals(ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum.IN_PROGRESS,
                followUp.getTask().getStatus());
        assertNull(followUp.getTask().getCompletedAt());
        assertEquals(followUp.getInitialRun().getId(), followUp.getTask().getCurrentRunId());
        assertEquals(2, taskService.listRuns(initial.getTask().getId()).size());
    }

    @Test
    void createsFollowUpRunWithMentionedAgent() {
        AgentDefinition assigned = createAgentWithScope(scope(7L, "sales", null, List.of()));
        AgentDefinition mentioned = createAgentWithScope(scope(7L, "sales", null, List.of()));
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setTitle("Hand off the analysis");
        request.setAssigneeAgentId(assigned.getId());
        AgentTaskCreation initial = taskService.create(request);
        AgentRun running = runService.transition(transition(
                initial.getInitialRun().getId(), 1L, AgentRunStatusEnum.RUNNING, null, null));
        runService.transition(transition(running.getId(), 2L, AgentRunStatusEnum.COMPLETED, null, "Done"));

        AgentTaskCreation followUp = taskService.createRun(
                initial.getTask().getId(), AgentRunTriggerTypeEnum.USER_MESSAGE, mentioned.getId());

        assertEquals(mentioned.getId(), followUp.getInitialRun().getAgentId());
        assertEquals(assigned.getId(), followUp.getTask().getAssigneeAgentId());
    }

    @Test
    void explicitlySyncsAssignedAgentScopesIntoAnExistingTaskSnapshot() {
        AgentDefinition agent = createAgentWithScope(scope(7L, "sales", "public", List.of("orders")));
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setTitle("Inspect databases");
        request.setAssigneeAgentId(agent.getId());
        AgentTaskCreation creation = taskService.create(request);
        AgentRun running = runService.transition(transition(
                creation.getInitialRun().getId(), 1L, AgentRunStatusEnum.RUNNING, null, null));
        runService.transition(transition(running.getId(), 2L, AgentRunStatusEnum.COMPLETED, null, "No scope"));

        AgentTask updated = taskService.syncAssignedAgentScopes(creation.getTask().getId(), 1L);

        assertEquals(2L, updated.getRevision());
        assertEquals(1, updated.getDataScopeSnapshot().size());
        assertEquals(7L, updated.getDataScopeSnapshot().get(0).getDataSourceId());
        assertEquals("sales", updated.getDataScopeSnapshot().get(0).getDatabaseName());
        assertEquals(List.of("orders"), updated.getDataScopeSnapshot().get(0).getTableNames());
        assertNotNull(updated.getDataScopeSyncedAt());
        assertEquals(agent.getRevision(), updated.getDataScopeSyncedFromAgentRevision());
    }

    @Test
    void archivesThenPermanentlyDeletesTask() {
        AgentDefinition agent = createAgentWithScope(scope(7L, "sales", null, List.of()));
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setTitle("Archive me");
        request.setAssigneeAgentId(agent.getId());
        AgentTaskCreation creation = taskService.create(request);
        AgentRun running = runService.transition(transition(
                creation.getInitialRun().getId(), 1L, AgentRunStatusEnum.RUNNING, null, null));
        runService.transition(transition(running.getId(), 2L, AgentRunStatusEnum.COMPLETED, null, "Done"));

        AgentTask archived = taskService.archive(creation.getTask().getId(), 1L);

        assertNotNull(archived.getArchivedAt());
        assertEquals(List.of(), taskService.list());
        assertEquals(List.of(archived), taskService.listArchived());
        assertThrows(IllegalStateException.class, () -> taskService.createRun(
                archived.getId(), AgentRunTriggerTypeEnum.USER_MESSAGE));

        taskService.deleteArchived(archived.getId(), archived.getRevision());
        assertThrows(java.util.NoSuchElementException.class, () -> taskService.get(archived.getId()));
    }

    private AgentDefinition createAgentWithScope(AgentDataScope scope) {
        AgentDefinitionCreateRequest request = new AgentDefinitionCreateRequest();
        request.setName("Agent " + storage.agents.size());
        request.setCapabilities(new LinkedHashSet<>(List.of(AgentCapabilityEnum.DATA_READ)));
        request.setDataScopes(List.of(scope));
        return agentService.create(request);
    }

    private static AgentDataScope scope(Long dataSourceId, String database, String schema, List<String> tables) {
        AgentDataScope scope = new AgentDataScope();
        scope.setDataSourceId(dataSourceId);
        scope.setDatabaseName(database);
        scope.setSchemaName(schema);
        scope.setTableNames(new ArrayList<>(tables));
        return scope;
    }

    private static AgentRunTransitionRequest transition(String runId, Long revision, AgentRunStatusEnum status,
                                                        String failureReason, String summary) {
        AgentRunTransitionRequest request = new AgentRunTransitionRequest();
        request.setRunId(runId);
        request.setExpectedRevision(revision);
        request.setTargetStatus(status);
        request.setFailureReason(failureReason);
        request.setResultSummary(summary);
        return request;
    }

    static final class MemoryAgentControlStorage implements IAgentControlStorage {
        private final Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        private final Map<String, AgentTask> tasks = new LinkedHashMap<>();
        private final Map<String, AgentRun> runs = new LinkedHashMap<>();
        private final List<AgentRunEvent> events = new ArrayList<>();
        private final List<AgentTaskContext> contexts = new ArrayList<>();
        private final Map<String, AgentArtifact> artifacts = new LinkedHashMap<>();
        private final Map<String, List<AgentArtifactVersion>> artifactVersions = new LinkedHashMap<>();
        private final Map<String, List<AgentArtifactEvidence>> artifactEvidence = new LinkedHashMap<>();
        private final Map<String, AgentSqlProposal> proposals = new LinkedHashMap<>();
        private final Map<String, AgentApproval> approvals = new LinkedHashMap<>();
        private final Map<String, AgentToolAttempt> attempts = new LinkedHashMap<>();
        private final Map<String, AgentArtifactDashboardRef> dashboardRefs = new LinkedHashMap<>();

        @Override
        public AgentDefinition createAgent(AgentDefinition agent) {
            agents.put(agent.getId(), agent);
            return agent;
        }

        @Override
        public AgentDefinition updateAgent(AgentDefinition agent, long expectedRevision) {
            AgentDefinition current = agents.get(agent.getId());
            if (current == null || current.getRevision() != expectedRevision) {
                throw new java.util.ConcurrentModificationException();
            }
            agents.put(agent.getId(), agent);
            return agent;
        }

        @Override
        public AgentDefinition getAgent(String id) {
            return agents.get(id);
        }

        @Override
        public List<AgentDefinition> listAgents() {
            return new ArrayList<>(agents.values());
        }

        @Override
        public AgentTaskCreation createTaskWithInitialRun(AgentTask task, AgentRun run) {
            tasks.put(task.getId(), task);
            runs.put(run.getId(), run);
            return new AgentTaskCreation(task, run);
        }

        @Override
        public AgentTaskCreation appendTaskRun(AgentTask task, AgentRun run, long expectedTaskRevision) {
            AgentTask current = tasks.get(task.getId());
            if (current == null || current.getRevision() != expectedTaskRevision) {
                throw new java.util.ConcurrentModificationException();
            }
            tasks.put(task.getId(), task);
            runs.put(run.getId(), run);
            return new AgentTaskCreation(task, run);
        }

        @Override
        public AgentTask getTask(String id) {
            return tasks.get(id);
        }

        @Override
        public List<AgentTask> listTasks() {
            return tasks.values().stream().filter(task -> task.getArchivedAt() == null).toList();
        }

        @Override
        public List<AgentTask> listArchivedTasks() {
            return tasks.values().stream().filter(task -> task.getArchivedAt() != null).toList();
        }

        @Override
        public AgentTask updateTask(AgentTask task, long expectedRevision) {
            AgentTask persisted = tasks.get(task.getId());
            if (persisted == null || persisted.getRevision() != expectedRevision) {
                throw new java.util.ConcurrentModificationException();
            }
            tasks.put(task.getId(), task);
            return task;
        }

        @Override
        public void deleteTask(String taskId, long expectedRevision) {
            AgentTask task = tasks.get(taskId);
            if (task == null || task.getRevision() != expectedRevision || task.getArchivedAt() == null) {
                throw new java.util.ConcurrentModificationException();
            }
            tasks.remove(taskId);
            runs.values().removeIf(run -> run.getTaskId().equals(taskId));
        }

        @Override
        public AgentRun getRun(String id) {
            return runs.get(id);
        }

        @Override
        public List<AgentRun> listRunsByTask(String taskId) {
            return runs.values().stream().filter(run -> run.getTaskId().equals(taskId)).toList();
        }

        @Override
        public AgentRun updateRun(AgentRun run, long expectedRevision) {
            AgentRun persisted = runs.get(run.getId());
            if (persisted == null || persisted.getRevision() != expectedRevision) {
                throw new java.util.ConcurrentModificationException();
            }
            runs.put(run.getId(), run);
            return run;
        }

        @Override
        public AgentRunEvent appendRunEvent(AgentRunEvent event) {
            AgentRunEvent existing = events.stream()
                    .filter(value -> value.getEventId().equals(event.getEventId()))
                    .findFirst().orElse(null);
            if (existing != null) {
                return existing;
            }
            event.setSequence((long) events.size() + 1);
            events.add(event);
            return event;
        }

        @Override
        public List<AgentRunEvent> listRunEvents(String runId) {
            return events.stream().filter(event -> event.getRunId().equals(runId)).toList();
        }

        @Override
        public AgentTaskContext appendTaskContext(AgentTaskContext context) {
            contexts.add(context);
            return context;
        }

        @Override
        public List<AgentTaskContext> listTaskContexts(String taskId) {
            return contexts.stream().filter(context -> context.getTaskId().equals(taskId)).toList();
        }

        @Override
        public AgentArtifactDetail createArtifact(AgentArtifact artifact, AgentArtifactVersion version,
                                                  List<AgentArtifactEvidence> evidence) {
            AgentArtifact existing = artifact.getCreatedByRunId() == null ? null : getArtifactByRunAndType(
                    artifact.getTaskId(), artifact.getCreatedByRunId(), artifact.getType());
            if (existing != null) {
                return artifactDetail(existing.getId());
            }
            artifacts.put(artifact.getId(), artifact);
            artifactVersions.put(artifact.getId(), new ArrayList<>(List.of(version)));
            artifactEvidence.put(artifact.getId(), new ArrayList<>(evidence));
            return artifactDetail(artifact.getId());
        }

        @Override
        public AgentArtifactDetail appendArtifactVersion(AgentArtifact artifact, AgentArtifactVersion version,
                                                         List<AgentArtifactEvidence> evidence,
                                                         long expectedRevision) {
            AgentArtifact persisted = artifacts.get(artifact.getId());
            if (persisted == null || persisted.getRevision() != expectedRevision) {
                throw new java.util.ConcurrentModificationException();
            }
            artifacts.put(artifact.getId(), artifact);
            artifactVersions.computeIfAbsent(artifact.getId(), key -> new ArrayList<>()).add(version);
            artifactEvidence.computeIfAbsent(artifact.getId(), key -> new ArrayList<>()).addAll(evidence);
            return artifactDetail(artifact.getId());
        }

        @Override
        public AgentArtifact getArtifact(String id) {
            return artifacts.get(id);
        }

        @Override
        public AgentArtifact getArtifactByRunAndType(String taskId, String runId, AgentArtifactTypeEnum type) {
            return artifacts.values().stream()
                    .filter(artifact -> artifact.getTaskId().equals(taskId))
                    .filter(artifact -> java.util.Objects.equals(artifact.getCreatedByRunId(), runId))
                    .filter(artifact -> artifact.getType() == type)
                    .findFirst().orElse(null);
        }

        @Override
        public List<AgentArtifact> listArtifactsByTask(String taskId) {
            return artifacts.values().stream().filter(artifact -> artifact.getTaskId().equals(taskId)).toList();
        }

        @Override
        public List<AgentArtifactVersion> listArtifactVersions(String artifactId) {
            return List.copyOf(artifactVersions.getOrDefault(artifactId, List.of()));
        }

        @Override
        public List<AgentArtifactEvidence> listArtifactEvidence(String artifactId) {
            return List.copyOf(artifactEvidence.getOrDefault(artifactId, List.of()));
        }

        private AgentArtifactDetail artifactDetail(String artifactId) {
            AgentArtifactDetail detail = new AgentArtifactDetail();
            detail.setArtifact(getArtifact(artifactId));
            detail.setVersions(listArtifactVersions(artifactId));
            detail.setEvidence(listArtifactEvidence(artifactId));
            return detail;
        }

        @Override
        public AgentSqlProposal createSqlProposal(AgentSqlProposal proposal, AgentApproval approval) {
            proposals.values().stream()
                    .filter(value -> value.getRunId().equals(proposal.getRunId()))
                    .filter(value -> value.getStatus() == ai.chat2db.community.domain.api.enums.agent.AgentSqlProposalStatusEnum.ACTIVE)
                    .forEach(value -> value.setStatus(
                            ai.chat2db.community.domain.api.enums.agent.AgentSqlProposalStatusEnum.SUPERSEDED));
            approvals.values().stream()
                    .filter(value -> value.getRunId().equals(proposal.getRunId()))
                    .filter(value -> value.getStatus() == ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatusEnum.PENDING)
                    .forEach(value -> value.setStatus(
                            ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatusEnum.EXPIRED));
            proposals.put(proposal.getId(), proposal);
            if (approval != null) approvals.put(approval.getId(), approval);
            return proposal;
        }

        @Override
        public AgentSqlProposal getSqlProposal(String id) {
            return proposals.get(id);
        }

        @Override
        public AgentSqlProposal findSqlProposal(String runId, String sqlHash, Long dataSourceId,
                                                String databaseName, String schemaName) {
            return proposals.values().stream()
                    .filter(value -> value.getRunId().equals(runId) && value.getSqlHash().equals(sqlHash))
                    .filter(value -> java.util.Objects.equals(value.getDataSourceId(), dataSourceId))
                    .filter(value -> java.util.Objects.equals(value.getDatabaseName(), databaseName))
                    .filter(value -> java.util.Objects.equals(value.getSchemaName(), schemaName))
                    .reduce((first, second) -> second).orElse(null);
        }

        @Override
        public List<AgentSqlProposal> listSqlProposals(String runId) {
            return proposals.values().stream().filter(value -> value.getRunId().equals(runId)).toList();
        }

        @Override
        public AgentSqlProposal updateSqlProposal(AgentSqlProposal proposal, long expectedRevision) {
            AgentSqlProposal current = proposals.get(proposal.getId());
            if (current == null || current.getRevision() != expectedRevision) throw new java.util.ConcurrentModificationException();
            proposals.put(proposal.getId(), proposal);
            return proposal;
        }

        @Override
        public AgentApproval getApproval(String id) {
            return approvals.get(id);
        }

        @Override
        public AgentApproval findApprovalByProposal(String proposalId) {
            return approvals.values().stream().filter(value -> value.getProposalId().equals(proposalId))
                    .findFirst().orElse(null);
        }

        @Override
        public List<AgentApproval> listApprovals(String runId) {
            return approvals.values().stream().filter(value -> value.getRunId().equals(runId)).toList();
        }

        @Override
        public AgentApproval updateApproval(AgentApproval approval, long expectedRevision) {
            AgentApproval current = approvals.get(approval.getId());
            if (current == null || current.getRevision() != expectedRevision) throw new java.util.ConcurrentModificationException();
            approvals.put(approval.getId(), approval);
            return approval;
        }

        @Override
        public AgentToolAttempt createOrGetToolAttempt(AgentToolAttempt attempt) {
            AgentToolAttempt existing = attempts.values().stream()
                    .filter(value -> value.getRunId().equals(attempt.getRunId()))
                    .filter(value -> value.getProposalVersion().equals(attempt.getProposalVersion()))
                    .filter(value -> value.getToolCallId().equals(attempt.getToolCallId()))
                    .findFirst().orElse(null);
            if (existing != null) return existing;
            attempts.put(attempt.getId(), attempt);
            return attempt;
        }

        @Override
        public AgentToolAttempt getToolAttempt(String id) {
            return attempts.get(id);
        }

        @Override
        public List<AgentToolAttempt> listToolAttempts(String runId) {
            return attempts.values().stream().filter(value -> value.getRunId().equals(runId)).toList();
        }

        @Override
        public AgentToolAttempt updateToolAttempt(AgentToolAttempt attempt, long expectedRevision) {
            AgentToolAttempt current = attempts.get(attempt.getId());
            if (current == null || current.getRevision() != expectedRevision) throw new java.util.ConcurrentModificationException();
            attempts.put(attempt.getId(), attempt);
            return attempt;
        }

        @Override
        public AgentArtifactDashboardRef createOrGetArtifactDashboardRef(AgentArtifactDashboardRef reference) {
            AgentArtifactDashboardRef existing = dashboardRefs.values().stream()
                    .filter(value -> value.getArtifactId().equals(reference.getArtifactId()))
                    .filter(value -> value.getArtifactVersion().equals(reference.getArtifactVersion()))
                    .filter(value -> value.getChartIndex().equals(reference.getChartIndex()))
                    .filter(value -> value.getDashboardId().equals(reference.getDashboardId()))
                    .filter(value -> value.getContentMode() == reference.getContentMode())
                    .findFirst().orElse(null);
            if (existing != null) return existing;
            dashboardRefs.put(reference.getId(), reference);
            return reference;
        }

        @Override
        public List<AgentArtifactDashboardRef> listArtifactDashboardRefs(String taskId) {
            return dashboardRefs.values().stream()
                    .filter(value -> value.getTaskId().equals(taskId)).toList();
        }

        @Override
        public AgentArtifactDashboardRef getArtifactDashboardRefByChartId(Long chartId) {
            return dashboardRefs.values().stream()
                    .filter(value -> value.getChartId().equals(chartId)).findFirst().orElse(null);
        }
    }
}

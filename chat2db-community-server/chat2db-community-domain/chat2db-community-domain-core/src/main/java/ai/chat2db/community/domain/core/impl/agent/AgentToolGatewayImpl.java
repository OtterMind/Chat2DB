package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecisionEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalModeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRiskLevelEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeLeaseStateEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlOperationClassEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlPermitDecisionEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlProposalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentToolAttemptStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunLease;
import ai.chat2db.community.domain.api.model.agent.AgentSqlExecutionPermit;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentToolAttempt;
import ai.chat2db.community.domain.api.model.request.agent.AgentApprovalDecisionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunTransitionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentSqlToolRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskTransitionRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentRunService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskService;
import ai.chat2db.community.domain.api.service.agent.IAgentToolGateway;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentRuntimeControlStorage;
import ai.chat2db.community.domain.core.impl.ai.AgentToolScopePolicy;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentToolGatewayImpl implements IAgentToolGateway {

    private final IAgentControlStorage storage;
    private final IAgentRunService runService;
    private final IAgentTaskService taskService;
    private IAgentRuntimeControlStorage runtimeStorage;

    public AgentToolGatewayImpl(IAgentControlStorage storage, IAgentRunService runService,
                                IAgentTaskService taskService) {
        this.storage = storage;
        this.runService = runService;
        this.taskService = taskService;
    }

    @Autowired(required = false)
    void setRuntimeStorage(IAgentRuntimeControlStorage runtimeStorage) {
        this.runtimeStorage = runtimeStorage;
    }

    @Override
    public AgentSqlExecutionPermit prepareSql(AgentSqlToolRequest request) {
        validateRequest(request);
        AgentRun run = runService.get(request.getRunId());
        if (run.getStatus() != AgentRunStatusEnum.RUNNING
                && run.getStatus() != AgentRunStatusEnum.WAITING_APPROVAL) {
            throw new IllegalStateException("agent SQL tools require a running or approval-waiting run");
        }
        AgentTask task = requireTask(run.getTaskId());
        AgentDefinition agent = requireAgent(run.getAgentId());
        AgentDataScope scope = resolveScope(task, request);
        AgentToolScopePolicy.requireConnection(scope, request.getDataSourceId(),
                request.getDatabaseName(), request.getSchemaName());
        AgentToolScopePolicy.requireSql(scope, request.getSql());

        AgentSqlOperationClassEnum operation = classify(request.getSql());
        requireCapability(agent, operation);
        if (operation == AgentSqlOperationClassEnum.ADMIN) {
            return denied("Administrative SQL is not supported by the Agent Tool Gateway");
        }
        String sqlHash = sha256(request.getSql().trim());
        AgentSqlProposal proposal = storage.findSqlProposal(run.getId(), sqlHash,
                request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName());
        if (proposal == null) {
            proposal = createProposal(run, agent, scope, request, operation, sqlHash);
        }
        AgentApproval approval = storage.findApprovalByProposal(proposal.getId());
        if (approval != null) {
            if (approval.getStatus() == AgentApprovalStatusEnum.PENDING) {
                moveRunToWaitingApproval(runService.get(run.getId()));
                return approvalRequired(proposal, approval);
            }
            if (approval.getStatus() != AgentApprovalStatusEnum.APPROVED
                    || !Objects.equals(approval.getProposalHash(), proposal.getSqlHash())) {
                return denied("SQL proposal is not approved or its approval is no longer valid");
            }
        }

        String toolCallId = StringUtils.defaultIfBlank(request.getToolCallId(),
                sha256(run.getId() + ":" + proposal.getProposalVersion() + ":" + proposal.getSqlHash()));
        AgentToolAttempt prepared = new AgentToolAttempt();
        prepared.setId(UUID.randomUUID().toString());
        prepared.setRunId(run.getId());
        prepared.setProposalId(proposal.getId());
        prepared.setProposalVersion(proposal.getProposalVersion());
        prepared.setToolCallId(toolCallId);
        prepared.setToolName("execute_sql");
        prepared.setStatus(AgentToolAttemptStatusEnum.PREPARED);
        prepared.setWriteOperation(operation != AgentSqlOperationClassEnum.READ);
        prepared.setPreparedAt(new Date());
        prepared.setRevision(1L);
        AgentToolAttempt attempt = storage.createOrGetToolAttempt(prepared);
        if (proposal.getStatus() == AgentSqlProposalStatusEnum.EXECUTED
                && operation != AgentSqlOperationClassEnum.READ
                && attempt.getStatus() == AgentToolAttemptStatusEnum.PREPARED) {
            return denied("Executed write proposal cannot create another tool attempt");
        }
        return claimOrReplay(proposal, approval, attempt);
    }

    @Override
    public AgentToolAttempt markSucceeded(String attemptId, String resultContent) {
        AgentToolAttempt current = requireAttempt(attemptId);
        if (current.getStatus() == AgentToolAttemptStatusEnum.SUCCEEDED) {
            return current;
        }
        if (current.getStatus() != AgentToolAttemptStatusEnum.EXECUTING) {
            throw new IllegalStateException("only an executing tool attempt can succeed");
        }
        AgentToolAttempt updated = copy(current);
        updated.setStatus(AgentToolAttemptStatusEnum.SUCCEEDED);
        updated.setResultContent(resultContent);
        updated.setCompletedAt(new Date());
        updated.setRevision(current.getRevision() + 1);
        AgentToolAttempt persisted = storage.updateToolAttempt(updated, current.getRevision());
        markProposalExecuted(current.getProposalId());
        return persisted;
    }

    @Override
    public AgentToolAttempt markFailed(String attemptId, String errorMessage, boolean outcomeUnknown) {
        AgentToolAttempt current = requireAttempt(attemptId);
        if (current.getStatus() == AgentToolAttemptStatusEnum.UNKNOWN
                || current.getStatus() == AgentToolAttemptStatusEnum.FAILED) {
            return current;
        }
        if (current.getStatus() != AgentToolAttemptStatusEnum.EXECUTING) {
            throw new IllegalStateException("only an executing tool attempt can fail");
        }
        AgentToolAttempt updated = copy(current);
        updated.setStatus(outcomeUnknown ? AgentToolAttemptStatusEnum.UNKNOWN : AgentToolAttemptStatusEnum.FAILED);
        updated.setErrorMessage(StringUtils.defaultIfBlank(errorMessage, "tool execution failed"));
        updated.setCompletedAt(new Date());
        updated.setRevision(current.getRevision() + 1);
        return storage.updateToolAttempt(updated, current.getRevision());
    }

    @Override
    public AgentApproval decide(AgentApprovalDecisionRequest request) {
        validateDecision(request);
        AgentApproval current = getApproval(request.getApprovalId());
        if (current.getRevision() == null || !current.getRevision().equals(request.getExpectedRevision())) {
            throw new IllegalStateException("approval revision has changed; refresh before deciding");
        }
        if (current.getStatus() != AgentApprovalStatusEnum.PENDING) {
            throw new IllegalStateException("only pending approvals can be decided");
        }
        AgentSqlProposal proposal = requireProposal(current.getProposalId());
        if (proposal.getStatus() != AgentSqlProposalStatusEnum.ACTIVE
                || !Objects.equals(current.getProposalHash(), proposal.getSqlHash())
                || !Objects.equals(current.getProposalVersion(), proposal.getProposalVersion())) {
            throw new IllegalStateException("approval proposal is stale or has been superseded");
        }

        AgentApproval updated = copy(current);
        updated.setDecision(request.getDecision());
        updated.setStatus(request.getDecision() == AgentApprovalDecisionEnum.APPROVE
                ? AgentApprovalStatusEnum.APPROVED : AgentApprovalStatusEnum.REJECTED);
        updated.setReason(StringUtils.trimToNull(request.getReason()));
        updated.setDecidedBy(request.getDecidedBy());
        updated.setDecidedAt(new Date());
        updated.setRevision(current.getRevision() + 1);
        AgentApproval persisted = storage.updateApproval(updated, current.getRevision());

        AgentRun run = runService.get(current.getRunId());
        AgentTask approvalTask = taskService.get(run.getTaskId());
        if (approvalTask.getOriginType() == AgentTaskOriginTypeEnum.CONNECTOR) {
            if (run.getStatus() == AgentRunStatusEnum.WAITING_APPROVAL) {
                transitionRun(run, request.getDecision() == AgentApprovalDecisionEnum.APPROVE
                        ? AgentRunStatusEnum.RUNNING : AgentRunStatusEnum.FAILED,
                        request.getDecision() == AgentApprovalDecisionEnum.APPROVE
                                ? null : "SQL proposal was rejected");
            }
        } else if (request.getDecision() == AgentApprovalDecisionEnum.APPROVE) {
            if (run.getStatus() == AgentRunStatusEnum.WAITING_APPROVAL) {
                if (run.getRuntimeType() == AgentRuntimeTypeEnum.EXTERNAL_AGENT
                        && hasReleasedExternalLease(run.getId())) {
                    transitionRun(run, AgentRunStatusEnum.QUEUED, null);
                    moveTaskTo(run.getTaskId(), AgentTaskStatusEnum.WAITING_APPROVAL,
                            AgentTaskStatusEnum.IN_PROGRESS);
                } else if (run.getRuntimeType() != AgentRuntimeTypeEnum.EXTERNAL_AGENT) {
                    transitionRun(run, AgentRunStatusEnum.RUNNING, null);
                    moveTaskTo(run.getTaskId(), AgentTaskStatusEnum.WAITING_APPROVAL,
                            AgentTaskStatusEnum.IN_PROGRESS);
                }
            }
        } else if (run.getStatus() == AgentRunStatusEnum.WAITING_APPROVAL) {
            if (run.getRuntimeType() == AgentRuntimeTypeEnum.EXTERNAL_AGENT) {
                if (hasReleasedExternalLease(run.getId())) {
                    transitionRun(run, AgentRunStatusEnum.QUEUED, null);
                    moveTaskTo(run.getTaskId(), AgentTaskStatusEnum.WAITING_APPROVAL,
                            AgentTaskStatusEnum.IN_PROGRESS);
                }
            } else {
                transitionRun(run, AgentRunStatusEnum.FAILED, "SQL proposal was rejected");
                moveTaskTo(run.getTaskId(), AgentTaskStatusEnum.WAITING_APPROVAL,
                        AgentTaskStatusEnum.BLOCKED);
            }
        }
        persistApprovalDecisionEvent(persisted);
        return persisted;
    }

    private boolean hasReleasedExternalLease(String runId) {
        if (runtimeStorage == null) {
            return false;
        }
        AgentRuntimeRunLease lease = runtimeStorage.getRuntimeRunLease(runId);
        return lease == null || lease.getState() != AgentRuntimeLeaseStateEnum.ACTIVE;
    }

    @Override
    public AgentApproval getApproval(String approvalId) {
        if (StringUtils.isBlank(approvalId)) {
            throw new IllegalArgumentException("approval id is required");
        }
        AgentApproval approval = storage.getApproval(approvalId);
        if (approval == null) {
            throw new NoSuchElementException("approval not found: " + approvalId);
        }
        return approval;
    }

    @Override
    public List<AgentApproval> listApprovals(String runId) {
        runService.get(runId);
        return storage.listApprovals(runId);
    }

    @Override
    public List<AgentSqlProposal> listProposals(String runId) {
        runService.get(runId);
        return storage.listSqlProposals(runId);
    }

    @Override
    public List<AgentToolAttempt> listAttempts(String runId) {
        runService.get(runId);
        return storage.listToolAttempts(runId);
    }

    private AgentSqlExecutionPermit claimOrReplay(AgentSqlProposal proposal, AgentApproval approval,
                                                  AgentToolAttempt attempt) {
        AgentSqlExecutionPermit permit = new AgentSqlExecutionPermit();
        permit.setProposal(proposal);
        permit.setApproval(approval);
        permit.setAttempt(attempt);
        switch (attempt.getStatus()) {
            case SUCCEEDED -> {
                permit.setDecision(AgentSqlPermitDecisionEnum.REPLAY_RESULT);
                permit.setReplayResult(attempt.getResultContent());
                return permit;
            }
            case UNKNOWN -> {
                permit.setDecision(AgentSqlPermitDecisionEnum.DENIED);
                permit.setMessage("Previous write outcome is UNKNOWN and cannot be retried automatically");
                return permit;
            }
            case EXECUTING -> {
                permit.setDecision(AgentSqlPermitDecisionEnum.DENIED);
                permit.setMessage("Tool attempt is already executing");
                return permit;
            }
            case FAILED -> {
                if (Boolean.TRUE.equals(attempt.getWriteOperation())) {
                    permit.setDecision(AgentSqlPermitDecisionEnum.DENIED);
                    permit.setMessage("Failed write attempt requires an explicit new proposal");
                    return permit;
                }
            }
            case PREPARED -> {
            }
        }
        AgentToolAttempt executing = copy(attempt);
        executing.setStatus(AgentToolAttemptStatusEnum.EXECUTING);
        executing.setExecutingAt(new Date());
        executing.setCompletedAt(null);
        executing.setErrorMessage(null);
        executing.setRevision(attempt.getRevision() + 1);
        permit.setAttempt(storage.updateToolAttempt(executing, attempt.getRevision()));
        permit.setDecision(AgentSqlPermitDecisionEnum.EXECUTE);
        return permit;
    }

    private AgentSqlProposal createProposal(AgentRun run, AgentDefinition agent, AgentDataScope scope,
                                            AgentSqlToolRequest request, AgentSqlOperationClassEnum operation,
                                            String sqlHash) {
        int version = storage.listSqlProposals(run.getId()).stream()
                .map(AgentSqlProposal::getProposalVersion).max(Integer::compareTo).orElse(0) + 1;
        Date now = new Date();
        AgentSqlProposal proposal = new AgentSqlProposal();
        proposal.setId(UUID.randomUUID().toString());
        proposal.setRunId(run.getId());
        proposal.setProposalVersion(version);
        proposal.setSqlSnapshot(request.getSql().trim());
        proposal.setSqlHash(sqlHash);
        proposal.setDataSourceId(request.getDataSourceId());
        proposal.setDatabaseName(StringUtils.trimToNull(request.getDatabaseName()));
        proposal.setSchemaName(StringUtils.trimToNull(request.getSchemaName()));
        proposal.setOperationClass(operation);
        proposal.setRiskLevel(risk(operation));
        proposal.setEstimatedImpact(estimatedImpact(operation));
        proposal.setStatus(AgentSqlProposalStatusEnum.ACTIVE);
        proposal.setCreatedAt(now);
        proposal.setUpdatedAt(now);
        proposal.setRevision(1L);

        AgentApproval approval = null;
        if (requiresApproval(scope, operation)) {
            approval = new AgentApproval();
            approval.setId(UUID.randomUUID().toString());
            approval.setProposalId(proposal.getId());
            approval.setRunId(run.getId());
            approval.setProposalVersion(version);
            approval.setProposalHash(sqlHash);
            approval.setStatus(AgentApprovalStatusEnum.PENDING);
            approval.setRequestedBy(agent.getId());
            approval.setRequestedAt(now);
            approval.setRevision(1L);
        }
        AgentSqlProposal persisted = storage.createSqlProposal(proposal, approval);
        if (approval != null) {
            persistApprovalRequiredEvent(storage.findApprovalByProposal(persisted.getId()), persisted);
        }
        return persisted;
    }

    private AgentDataScope resolveScope(AgentTask task, AgentSqlToolRequest request) {
        List<AgentDataScope> matches = task.getDataScopeSnapshot().stream()
                .filter(scope -> Objects.equals(scope.getDataSourceId(), request.getDataSourceId()))
                .filter(scope -> contains(scope.getDatabaseName(), request.getDatabaseName()))
                .filter(scope -> contains(scope.getSchemaName(), request.getSchemaName()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("SQL target must resolve to exactly one Task data scope");
        }
        return matches.get(0);
    }

    private void requireCapability(AgentDefinition agent, AgentSqlOperationClassEnum operation) {
        AgentCapabilityEnum required = switch (operation) {
            case READ -> AgentCapabilityEnum.DATA_READ;
            case WRITE -> AgentCapabilityEnum.DATA_WRITE;
            case DDL -> AgentCapabilityEnum.DDL;
            case ADMIN -> null;
        };
        if (required == null || agent.getCapabilities() == null || !agent.getCapabilities().contains(required)) {
            throw new IllegalArgumentException("agent capability does not allow SQL operation: " + operation);
        }
    }

    private AgentSqlOperationClassEnum classify(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Select) {
                return AgentSqlOperationClassEnum.READ;
            }
            String kind = statement.getClass().getSimpleName().toUpperCase(Locale.ROOT);
            if (Set.of("SHOWSTATEMENT", "DESCRIBESTATEMENT", "EXPLAINSTATEMENT").contains(kind)) {
                return AgentSqlOperationClassEnum.READ;
            }
            if (Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE").contains(kind)) {
                return AgentSqlOperationClassEnum.WRITE;
            }
            if (kind.startsWith("CREATE") || kind.startsWith("ALTER") || kind.startsWith("DROP")
                    || kind.startsWith("TRUNCATE") || kind.startsWith("RENAME") || kind.startsWith("COMMENT")) {
                return AgentSqlOperationClassEnum.DDL;
            }
            return AgentSqlOperationClassEnum.ADMIN;
        } catch (Exception exception) {
            return AgentSqlOperationClassEnum.ADMIN;
        }
    }

    private AgentRiskLevelEnum risk(AgentSqlOperationClassEnum operation) {
        return switch (operation) {
            case READ -> AgentRiskLevelEnum.LOW;
            case WRITE -> AgentRiskLevelEnum.MEDIUM;
            case DDL -> AgentRiskLevelEnum.HIGH;
            case ADMIN -> AgentRiskLevelEnum.CRITICAL;
        };
    }

    private String estimatedImpact(AgentSqlOperationClassEnum operation) {
        return switch (operation) {
            case READ -> "Read-only query; result rows are limited by Task DataScope";
            case WRITE -> "May modify rows in the authorized Task DataScope";
            case DDL -> "May change database objects in the authorized Task DataScope";
            case ADMIN -> "Administrative operation is blocked";
        };
    }

    private boolean requiresApproval(AgentDataScope scope, AgentSqlOperationClassEnum operation) {
        AgentApprovalModeEnum mode = scope.getApprovalMode() == null
                ? AgentApprovalModeEnum.RISK_BASED : scope.getApprovalMode();
        return mode == AgentApprovalModeEnum.ALWAYS
                || (mode == AgentApprovalModeEnum.RISK_BASED && operation != AgentSqlOperationClassEnum.READ);
    }

    private void moveRunToWaitingApproval(AgentRun run) {
        if (run.getStatus() == AgentRunStatusEnum.RUNNING) {
            transitionRun(run, AgentRunStatusEnum.WAITING_APPROVAL, null);
            if (taskService.get(run.getTaskId()).getOriginType() != AgentTaskOriginTypeEnum.CONNECTOR) {
                moveTaskTo(run.getTaskId(), AgentTaskStatusEnum.IN_PROGRESS,
                        AgentTaskStatusEnum.WAITING_APPROVAL);
            }
        }
    }

    private void moveTaskTo(String taskId, AgentTaskStatusEnum expected, AgentTaskStatusEnum target) {
        AgentTask task = taskService.get(taskId);
        if (task.getStatus() != expected) {
            return;
        }
        AgentTaskTransitionRequest transition = new AgentTaskTransitionRequest();
        transition.setTaskId(taskId);
        transition.setExpectedRevision(task.getRevision());
        transition.setTargetStatus(target);
        taskService.transition(transition);
    }

    private AgentRun transitionRun(AgentRun run, AgentRunStatusEnum target, String failureReason) {
        AgentRunTransitionRequest transition = new AgentRunTransitionRequest();
        transition.setRunId(run.getId());
        transition.setExpectedRevision(run.getRevision());
        transition.setTargetStatus(target);
        transition.setFailureReason(failureReason);
        return runService.transition(transition);
    }

    private void markProposalExecuted(String proposalId) {
        AgentSqlProposal current = requireProposal(proposalId);
        if (current.getStatus() != AgentSqlProposalStatusEnum.ACTIVE) {
            return;
        }
        AgentSqlProposal updated = copy(current);
        updated.setStatus(AgentSqlProposalStatusEnum.EXECUTED);
        updated.setUpdatedAt(new Date());
        updated.setRevision(current.getRevision() + 1);
        storage.updateSqlProposal(updated, current.getRevision());
    }

    private AgentSqlExecutionPermit approvalRequired(AgentSqlProposal proposal, AgentApproval approval) {
        AgentSqlExecutionPermit permit = new AgentSqlExecutionPermit();
        permit.setDecision(AgentSqlPermitDecisionEnum.APPROVAL_REQUIRED);
        permit.setProposal(proposal);
        permit.setApproval(approval);
        permit.setMessage("SQL proposal requires approval before execution");
        return permit;
    }

    private AgentSqlExecutionPermit denied(String message) {
        AgentSqlExecutionPermit permit = new AgentSqlExecutionPermit();
        permit.setDecision(AgentSqlPermitDecisionEnum.DENIED);
        permit.setMessage(message);
        return permit;
    }

    private void persistApprovalRequiredEvent(AgentApproval approval, AgentSqlProposal proposal) {
        if (approval == null) {
            return;
        }
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId("approval-required-" + approval.getId());
        event.setRunId(approval.getRunId());
        event.setType(AgentRuntimeEventTypeEnum.APPROVAL_REQUIRED);
        event.setContent("SQL proposal requires approval");
        event.setPayload(new LinkedHashMap<>(Map.of(
                "approvalId", approval.getId(),
                "proposalId", proposal.getId(),
                "proposalVersion", proposal.getProposalVersion(),
                "riskLevel", proposal.getRiskLevel().name())));
        event.setOccurredAt(new Date());
        event.setPersistedAt(new Date());
        storage.appendRunEvent(event);
    }

    private void persistApprovalDecisionEvent(AgentApproval approval) {
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId("approval-decided-" + approval.getId() + "-" + approval.getRevision());
        event.setRunId(approval.getRunId());
        event.setType(AgentRuntimeEventTypeEnum.STATUS);
        event.setContent("APPROVAL_" + approval.getDecision().name());
        event.setPayload(new LinkedHashMap<>(Map.of(
                "approvalId", approval.getId(),
                "decision", approval.getDecision().name())));
        event.setOccurredAt(new Date());
        event.setPersistedAt(new Date());
        storage.appendRunEvent(event);
    }

    private AgentTask requireTask(String id) {
        AgentTask task = storage.getTask(id);
        if (task == null) {
            throw new NoSuchElementException("task not found: " + id);
        }
        return task;
    }

    private AgentDefinition requireAgent(String id) {
        AgentDefinition agent = storage.getAgent(id);
        if (agent == null) {
            throw new NoSuchElementException("agent not found: " + id);
        }
        return agent;
    }

    private AgentSqlProposal requireProposal(String id) {
        AgentSqlProposal proposal = storage.getSqlProposal(id);
        if (proposal == null) {
            throw new NoSuchElementException("SQL proposal not found: " + id);
        }
        return proposal;
    }

    private AgentToolAttempt requireAttempt(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("tool attempt id is required");
        }
        AgentToolAttempt attempt = storage.getToolAttempt(id);
        if (attempt == null) {
            throw new NoSuchElementException("tool attempt not found: " + id);
        }
        return attempt;
    }

    private void validateRequest(AgentSqlToolRequest request) {
        if (request == null || StringUtils.isBlank(request.getRunId()) || StringUtils.isBlank(request.getSql())) {
            throw new IllegalArgumentException("agent SQL request requires run id and SQL");
        }
        if (request.getDataSourceId() == null) {
            throw new IllegalArgumentException("agent SQL request requires datasource id");
        }
    }

    private void validateDecision(AgentApprovalDecisionRequest request) {
        if (request == null || StringUtils.isBlank(request.getApprovalId())) {
            throw new IllegalArgumentException("approval decision request and id are required");
        }
        if (request.getExpectedRevision() == null || request.getExpectedRevision() <= 0
                || request.getDecision() == null || request.getDecidedBy() == null) {
            throw new IllegalArgumentException("approval decision, positive revision and decision maker are required");
        }
    }

    private boolean contains(String granted, String requested) {
        return StringUtils.isBlank(granted)
                || (StringUtils.isNotBlank(requested) && granted.equalsIgnoreCase(requested));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private AgentSqlProposal copy(AgentSqlProposal source) {
        AgentSqlProposal copy = new AgentSqlProposal();
        copy.setId(source.getId()); copy.setRunId(source.getRunId());
        copy.setProposalVersion(source.getProposalVersion()); copy.setSqlSnapshot(source.getSqlSnapshot());
        copy.setSqlHash(source.getSqlHash()); copy.setDataSourceId(source.getDataSourceId());
        copy.setDatabaseName(source.getDatabaseName()); copy.setSchemaName(source.getSchemaName());
        copy.setOperationClass(source.getOperationClass()); copy.setRiskLevel(source.getRiskLevel());
        copy.setEstimatedImpact(source.getEstimatedImpact()); copy.setStatus(source.getStatus());
        copy.setCreatedAt(source.getCreatedAt()); copy.setUpdatedAt(source.getUpdatedAt());
        copy.setRevision(source.getRevision());
        return copy;
    }

    private AgentApproval copy(AgentApproval source) {
        AgentApproval copy = new AgentApproval();
        copy.setId(source.getId()); copy.setProposalId(source.getProposalId()); copy.setRunId(source.getRunId());
        copy.setProposalVersion(source.getProposalVersion()); copy.setProposalHash(source.getProposalHash());
        copy.setStatus(source.getStatus()); copy.setRequestedBy(source.getRequestedBy());
        copy.setRequestedAt(source.getRequestedAt()); copy.setDecidedBy(source.getDecidedBy());
        copy.setDecidedAt(source.getDecidedAt()); copy.setDecision(source.getDecision());
        copy.setReason(source.getReason()); copy.setRevision(source.getRevision());
        return copy;
    }

    private AgentToolAttempt copy(AgentToolAttempt source) {
        AgentToolAttempt copy = new AgentToolAttempt();
        copy.setId(source.getId()); copy.setRunId(source.getRunId()); copy.setProposalId(source.getProposalId());
        copy.setProposalVersion(source.getProposalVersion()); copy.setToolCallId(source.getToolCallId());
        copy.setToolName(source.getToolName()); copy.setStatus(source.getStatus());
        copy.setWriteOperation(source.getWriteOperation()); copy.setResultContent(source.getResultContent());
        copy.setErrorMessage(source.getErrorMessage()); copy.setPreparedAt(source.getPreparedAt());
        copy.setExecutingAt(source.getExecutingAt()); copy.setCompletedAt(source.getCompletedAt());
        copy.setRevision(source.getRevision());
        return copy;
    }
}

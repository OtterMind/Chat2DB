package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeMcpEndpoint;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApprovalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactManifest;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactResult;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeEventAccepted;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeLeaseStatus;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunClaim;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeEventRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeLeaseRenewRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCancelAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCompleteRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunFailRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunStartedRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunSuspendRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeArtifactUploadRequest;
import ai.chat2db.community.runtime.provider.ExternalProviderAdapter;
import ai.chat2db.community.runtime.provider.ProviderExecutionException;
import ai.chat2db.community.runtime.provider.ProviderExecutionRequest;
import ai.chat2db.community.runtime.provider.ProviderExecutionResult;
import ai.chat2db.community.runtime.provider.ProviderFailureKind;
import ai.chat2db.community.runtime.provider.ProviderMcpEndpoint;
import ai.chat2db.community.runtime.provider.ProviderLifecycleSink;
import ai.chat2db.community.runtime.provider.ProviderApprovalRequest;
import ai.chat2db.community.runtime.provider.ProviderApprovalDecision;
import ai.chat2db.community.runtime.workspace.TaskWorkspaceManager;
import ai.chat2db.community.runtime.workspace.RuntimeArtifactManifestReader;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ExternalRuntimeClaimExecutor {

    static final String TASK_TOKEN_ENV = "CHAT2DB_AGENT_TASK_TOKEN";

    private final String daemonId;
    private final AgentRuntimeControlPlaneClient controlPlane;
    private final ExternalProviderAdapter adapter;
    private final TaskWorkspaceManager workspaceManager;
    private final Clock clock;
    private final ProviderProcessRegistry processRegistry;
    private final RuntimeArtifactManifestReader artifactManifestReader = new RuntimeArtifactManifestReader();

    public ExternalRuntimeClaimExecutor(String daemonId, AgentRuntimeControlPlaneClient controlPlane,
                              ExternalProviderAdapter adapter, TaskWorkspaceManager workspaceManager) {
        this(daemonId, controlPlane, adapter, workspaceManager, Clock.systemUTC(),
                new ProviderProcessRegistry(workspaceManager.root()));
    }

    ExternalRuntimeClaimExecutor(String daemonId, AgentRuntimeControlPlaneClient controlPlane,
                       ExternalProviderAdapter adapter, TaskWorkspaceManager workspaceManager, Clock clock) {
        this(daemonId, controlPlane, adapter, workspaceManager, clock,
                new ProviderProcessRegistry(workspaceManager.root()));
    }

    ExternalRuntimeClaimExecutor(String daemonId, AgentRuntimeControlPlaneClient controlPlane,
                                 ExternalProviderAdapter adapter, TaskWorkspaceManager workspaceManager,
                                 Clock clock, ProviderProcessRegistry processRegistry) {
        if (StringUtils.isBlank(daemonId) || controlPlane == null || adapter == null
                || workspaceManager == null || clock == null || processRegistry == null) {
            throw new IllegalArgumentException("External Runtime claim executor dependencies are required");
        }
        this.daemonId = daemonId.trim();
        this.controlPlane = controlPlane;
        this.adapter = adapter;
        this.workspaceManager = workspaceManager;
        this.clock = clock;
        this.processRegistry = processRegistry;
    }

    public ProviderProcessRegistry.RecoveryReport recoverOrphanedProcesses() {
        ProviderProcessRegistry.RecoveryReport report = processRegistry.reapOrphans(daemonId, adapter.provider());
        for (ProviderProcessRegistry.Entry entry : report.recovered()) {
            if (StringUtils.isNotBlank(entry.getWorkspace())) {
                workspaceManager.cleanup(Path.of(entry.getWorkspace()));
            }
        }
        return report;
    }

    public void execute(AgentRuntimeRunClaim claim, Map<String, String> resolvedEnvironment) {
        validateClaim(claim, resolvedEnvironment);
        Path workspace = workspaceManager.create(claim.getRunId(), claim.getLeaseAttempt());
        LeaseCoordinator lease = new LeaseCoordinator(claim);
        ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat2db-runtime-lease-" + claim.getRunId());
            thread.setDaemon(true);
            return thread;
        });
        long renewEveryMillis = renewalInterval(claim);
        AtomicReference<Throwable> fatalLeaseFailure = new AtomicReference<>();
        renewer.scheduleWithFixedDelay(() -> {
            try {
                if (lease.renew()) {
                    adapter.cancel(claim.getRunId());
                }
            } catch (Throwable failure) {
                // A short Chat2DB restart must not immediately kill a healthy Provider.
                // Keep retrying while the last acknowledged lease is still valid. Once
                // that hard fence expires, stop local execution and report UNKNOWN.
                if (lease.expired()) {
                    fatalLeaseFailure.compareAndSet(null, failure);
                    adapter.cancel(claim.getRunId());
                }
            }
        }, renewEveryMillis, renewEveryMillis, TimeUnit.MILLISECONDS);

        try {
            ProviderExecutionRequest request = new ProviderExecutionRequest();
            request.setRunId(claim.getRunId());
            request.setLeaseAttempt(claim.getLeaseAttempt());
            request.setRuntimeProfile(claim.getRuntimeProfile());
            request.setStartRequest(claim.getStartRequest());
            request.setResumeSessionId(StringUtils.trimToNull(claim.getResumeSessionId()));
            request.setWorkingDirectory(workspace);
            request.setMcpEndpoints(resolveMcpEndpoints(claim));
            request.setApprovalHandler(lease::approval);
            request.setApprovalWaitingSupplier(lease::waitingForApproval);
            LinkedHashMap<String, String> executionEnvironment = new LinkedHashMap<>(resolvedEnvironment);
            executionEnvironment.put(TASK_TOKEN_ENV, claim.getTaskScopedToken());
            request.setEnvironment(Map.copyOf(executionEnvironment));

            ProviderLifecycleSink lifecycle = new ProviderLifecycleSink() {
                @Override
                public void started(String runtimeExecutionId) {
                    lease.started(runtimeExecutionId);
                }

                @Override
                public void processStarted(String runtimeExecutionId, long processId,
                                           java.time.Instant processStartInstant, String executable) {
                    processRegistry.register(daemonId, adapter.provider(), claim.getRunId(),
                            claim.getLeaseAttempt(), runtimeExecutionId, processId,
                            processStartInstant, executable, workspace);
                    lease.started(runtimeExecutionId);
                }
            };
            ProviderExecutionResult result = adapter.execute(request, lease::event, lifecycle);
            stopRenewer(renewer);
            Throwable renewalFailure = fatalLeaseFailure.get();
            if (renewalFailure != null) {
                lease.fail("Runtime lease coordination failed: " + safeMessage(renewalFailure), true);
            } else if (lease.shouldSuspendForSqlApproval()) {
                lease.suspendForSqlApproval();
            } else {
                List<AgentRuntimeArtifactManifest> artifacts = new java.util.ArrayList<>(
                        result.getArtifacts() == null ? List.of() : result.getArtifacts());
                try {
                    artifacts.addAll(artifactManifestReader.read(workspace));
                } catch (IllegalArgumentException rejectedManifest) {
                    lease.artifactRejected("Runtime artifact manifest is invalid and was ignored");
                }
                for (AgentRuntimeArtifactManifest artifact : artifacts) {
                    try {
                        lease.artifact(artifact);
                    } catch (ControlPlaneRejectedException rejectedArtifact) {
                        lease.artifactRejected("Runtime artifact was rejected by Chat2DB and was ignored");
                    }
                }
                lease.complete(result.getFinalResponse());
            }
        } catch (ProviderExecutionException failure) {
            stopRenewer(renewer);
            if (lease.shouldSuspendForSqlApproval()) {
                lease.suspendForSqlApproval();
            } else if (lease.cancelRequested.get() && failure.getFailureKind() == ProviderFailureKind.CANCELLED) {
                lease.cancelAck();
            } else {
                boolean outcomeUnknown = failure.getFailureKind() == ProviderFailureKind.PROCESS_EXIT
                        || fatalLeaseFailure.get() != null;
                lease.fail(safeMessage(fatalLeaseFailure.get() == null ? failure : fatalLeaseFailure.get()), outcomeUnknown);
            }
        } catch (RuntimeException failure) {
            stopRenewer(renewer);
            if (lease.shouldSuspendForSqlApproval()) {
                lease.suspendForSqlApproval();
            } else {
                lease.fail(safeMessage(failure), fatalLeaseFailure.get() != null);
            }
        } finally {
            stopRenewer(renewer);
            processRegistry.unregister(daemonId, claim.getRunId(), claim.getLeaseAttempt());
            workspaceManager.cleanup(workspace);
        }
    }

    public void failBeforeStart(AgentRuntimeRunClaim claim, String reason) {
        if (claim == null || claim.getLeaseRevision() == null || claim.getLeaseAttempt() == null
                || StringUtils.isBlank(claim.getRunId()) || StringUtils.isBlank(claim.getLeaseToken())) {
            throw new IllegalArgumentException("Cannot fail an incomplete Runtime claim");
        }
        new LeaseCoordinator(claim).fail(
                StringUtils.defaultIfBlank(reason, "Runtime preparation failed"), false);
    }

    private long renewalInterval(AgentRuntimeRunClaim claim) {
        long leaseInterval = claim.getLeaseExpiresAt() == null
                ? 15_000L : Math.max(250L,
                Math.min(20_000L, (claim.getLeaseExpiresAt().getTime() - clock.millis()) / 3L));
        Integer timeoutSeconds = claim.getRuntimeProfile().getTimeoutSeconds();
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return leaseInterval;
        }
        return Math.max(250L, Math.min(leaseInterval, timeoutSeconds * 250L));
    }

    private void stopRenewer(ScheduledExecutorService renewer) {
        renewer.shutdownNow();
        try {
            renewer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void validateClaim(AgentRuntimeRunClaim claim, Map<String, String> environment) {
        if (claim == null || StringUtils.isBlank(claim.getRunId()) || claim.getLeaseAttempt() == null
                || claim.getLeaseAttempt() <= 0 || claim.getLeaseRevision() == null
                || StringUtils.isBlank(claim.getLeaseToken()) || claim.getRuntimeProfile() == null
                || StringUtils.isBlank(claim.getTaskScopedToken()) || claim.getStartRequest() == null
                || claim.getMcpEndpoints() == null || claim.getMcpEndpoints().isEmpty()
                || environment == null) {
            throw new IllegalArgumentException("External Runtime execution claim is incomplete");
        }
        if (claim.getRuntimeProfile().getProvider() != adapter.provider()) {
            throw new IllegalArgumentException("Runtime executor cannot consume a claim for another provider");
        }
    }

    private List<ProviderMcpEndpoint> resolveMcpEndpoints(AgentRuntimeRunClaim claim) {
        return claim.getMcpEndpoints().stream().map(endpoint -> resolveMcpEndpoint(claim, endpoint)).toList();
    }

    private ProviderMcpEndpoint resolveMcpEndpoint(AgentRuntimeRunClaim claim,
                                                   AgentRuntimeMcpEndpoint endpoint) {
        if (endpoint == null || StringUtils.isBlank(endpoint.getName())
                || !endpoint.getName().matches("[A-Za-z0-9_-]+")
                || !"STREAMABLE_HTTP".equals(endpoint.getTransport())
                || !TASK_TOKEN_ENV.equals(endpoint.getBearerTokenEnvironmentVariable())
                || StringUtils.isBlank(endpoint.getPath())
                || !endpoint.getPath().endsWith("/" + claim.getRunId())) {
            throw new IllegalArgumentException("Runtime claim contains an invalid Task-scoped MCP endpoint");
        }
        ProviderMcpEndpoint resolved = new ProviderMcpEndpoint();
        resolved.setName(endpoint.getName());
        resolved.setUrl(controlPlane.resolveTaskMcpEndpoint(endpoint.getPath()));
        resolved.setBearerTokenEnvironmentVariable(TASK_TOKEN_ENV);
        return resolved;
    }

    private String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return StringUtils.defaultIfBlank(message, "External Runtime execution failed");
    }

    private final class LeaseCoordinator {
        private final AgentRuntimeRunClaim claim;
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean approvalDecisionPending = new AtomicBoolean();
        private final AtomicBoolean sqlContinuationAvailable = new AtomicBoolean();
        private final AtomicReference<AgentRunStatusEnum> runStatus = new AtomicReference<>();
        private long revision;
        private long sequence;
        private long leaseExpiresAtMillis;

        private LeaseCoordinator(AgentRuntimeRunClaim claim) {
            this.claim = claim;
            this.revision = claim.getLeaseRevision();
            this.leaseExpiresAtMillis = claim.getLeaseExpiresAt() == null
                    ? clock.millis() : claim.getLeaseExpiresAt().getTime();
        }

        private synchronized void started(String runtimeExecutionId) {
            AgentRuntimeRunStartedRequest request = new AgentRuntimeRunStartedRequest();
            leaseIdentity(request);
            request.setRuntimeExecutionId(runtimeExecutionId);
            update(controlPlane.started(claim.getRunId(), claim.getLeaseToken(), request));
        }

        private synchronized boolean renew() {
            AgentRuntimeLeaseRenewRequest request = new AgentRuntimeLeaseRenewRequest();
            leaseIdentity(request);
            AgentRuntimeLeaseStatus status = controlPlane.renew(
                    claim.getRunId(), claim.getLeaseToken(), request);
            update(status);
            if (Boolean.TRUE.equals(status.getCancelRequested())) {
                cancelRequested.set(true);
            }
            return cancelRequested.get();
        }

        private synchronized void event(AgentRuntimeEvent event) {
            AgentRuntimeEventRequest request = new AgentRuntimeEventRequest();
            leaseIdentity(request);
            request.setSequence(++sequence);
            request.setEventId(event.getEventId());
            request.setEventType(event.getType());
            request.setContent(redact(event.getContent()));
            request.setPayload(redactMap(event.getPayload()));
            request.setOccurredAt(event.getOccurredAt() == null ? now() : event.getOccurredAt());
            AgentRuntimeEventAccepted accepted = controlPlane.event(
                    claim.getRunId(), claim.getLeaseToken(), request);
            if (accepted == null || accepted.getLease() == null) {
                throw new ControlPlaneException("Runtime event response omitted lease status");
            }
            update(accepted.getLease());
        }

        private ProviderApprovalDecision approval(ProviderApprovalRequest providerRequest) {
            AgentRuntimeApproval approval;
            synchronized (this) {
                AgentRuntimeApprovalRequest request = new AgentRuntimeApprovalRequest();
                leaseIdentity(request);
                request.setProviderRequestId(providerRequest.getProviderRequestId());
                request.setToolCallId(providerRequest.getToolCallId());
                request.setTitle(redact(providerRequest.getTitle()));
                request.setRequestPayload(redactMap(providerRequest.getPayload()));
                request.setAllowOptionId(providerRequest.getAllowOptionId());
                request.setRejectOptionId(providerRequest.getRejectOptionId());
                AgentRuntimeApprovalResult result = controlPlane.requestApproval(
                        claim.getRunId(), claim.getLeaseToken(), request);
                approval = requireApprovalResult(result);
            }
            while (approval.getStatus() == AgentRuntimeApprovalStatusEnum.PENDING) {
                if (cancelRequested.get()) {
                    throw new ProviderExecutionException(
                            ProviderFailureKind.CANCELLED, "Runtime cancelled while waiting for approval");
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ProviderExecutionException(
                            ProviderFailureKind.CANCELLED, "Runtime approval wait was interrupted", exception);
                }
                try {
                    synchronized (this) {
                        AgentRuntimeApprovalAckRequest statusRequest = approvalIdentity(approval);
                        AgentRuntimeApprovalResult result = controlPlane.approvalStatus(
                                claim.getRunId(), claim.getLeaseToken(), statusRequest);
                        approval = requireApprovalResult(result);
                    }
                } catch (ControlPlaneException transientFailure) {
                    if (expired()) {
                        throw transientFailure;
                    }
                }
            }
            if (approval.getStatus() != AgentRuntimeApprovalStatusEnum.APPROVED
                    && approval.getStatus() != AgentRuntimeApprovalStatusEnum.REJECTED) {
                throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                        "Runtime approval expired before a user decision");
            }
            synchronized (this) {
                AgentRuntimeApprovalAckRequest ackRequest = approvalIdentity(approval);
                AgentRuntimeApprovalResult acknowledged = controlPlane.acknowledgeApproval(
                        claim.getRunId(), claim.getLeaseToken(), ackRequest);
                approval = requireApprovalResult(acknowledged);
            }
            ProviderApprovalDecision decision = new ProviderApprovalDecision();
            decision.setApproved(approval.getStatus() == AgentRuntimeApprovalStatusEnum.APPROVED);
            decision.setSelectedOptionId(decision.isApproved()
                    ? approval.getAllowOptionId() : approval.getRejectOptionId());
            decision.setReason(approval.getReason());
            return decision;
        }

        private synchronized void artifact(AgentRuntimeArtifactManifest manifest) {
            AgentRuntimeArtifactUploadRequest request = new AgentRuntimeArtifactUploadRequest();
            leaseIdentity(request);
            request.setManifest(manifest);
            AgentRuntimeArtifactResult result = controlPlane.uploadArtifact(
                    claim.getRunId(), claim.getLeaseToken(), request);
            if (result == null || result.getArtifact() == null || result.getLease() == null) {
                throw new ControlPlaneException("Runtime artifact response is incomplete");
            }
            update(result.getLease());
        }

        private synchronized void artifactRejected(String message) {
            AgentRuntimeEvent event = new AgentRuntimeEvent();
            event.setEventId("artifact-rejected-" + UUID.randomUUID());
            event.setRunId(claim.getRunId());
            event.setType(ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum.ERROR);
            event.setContent(message);
            event.setPayload(Map.of("recoverable", true, "scope", "ARTIFACT"));
            event.setOccurredAt(now());
            event(event);
        }

        private AgentRuntimeApprovalAckRequest approvalIdentity(AgentRuntimeApproval approval) {
            AgentRuntimeApprovalAckRequest request = new AgentRuntimeApprovalAckRequest();
            leaseIdentity(request);
            request.setApprovalId(approval.getId());
            request.setExpectedApprovalRevision(approval.getRevision());
            return request;
        }

        private AgentRuntimeApproval requireApprovalResult(AgentRuntimeApprovalResult result) {
            if (result == null || result.getApproval() == null || result.getLease() == null) {
                throw new ControlPlaneException("Runtime approval response is incomplete");
            }
            update(result.getLease());
            return result.getApproval();
        }

        private synchronized void complete(String finalResponse) {
            AgentRuntimeRunCompleteRequest request = new AgentRuntimeRunCompleteRequest();
            terminalIdentity(request);
            request.setFinalResponse(redact(finalResponse));
            controlPlane.complete(claim.getRunId(), claim.getLeaseToken(), request);
        }

        private synchronized void fail(String reason, boolean outcomeUnknown) {
            AgentRuntimeRunFailRequest request = new AgentRuntimeRunFailRequest();
            terminalIdentity(request);
            request.setFailureReason(redact(reason));
            request.setOutcomeUnknown(outcomeUnknown);
            controlPlane.fail(claim.getRunId(), claim.getLeaseToken(), request);
        }

        private synchronized void cancelAck() {
            AgentRuntimeRunCancelAckRequest request = new AgentRuntimeRunCancelAckRequest();
            terminalIdentity(request);
            controlPlane.cancelAck(claim.getRunId(), claim.getLeaseToken(), request);
        }

        private synchronized void suspendForSqlApproval() {
            AgentRuntimeRunSuspendRequest request = new AgentRuntimeRunSuspendRequest();
            terminalIdentity(request);
            update(controlPlane.suspendForSqlApproval(
                    claim.getRunId(), claim.getLeaseToken(), request));
        }

        private boolean shouldSuspendForSqlApproval() {
            try {
                synchronized (this) {
                    if (!expired()) {
                        renew();
                    }
                }
            } catch (ControlPlaneException ignored) {
                // Use the last acknowledged status while the lease is still
                // valid. The terminal/suspend call remains revision-fenced.
            }
            return runStatus.get() == AgentRunStatusEnum.WAITING_APPROVAL
                    && sqlContinuationAvailable.get();
        }

        private void terminalIdentity(ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunTerminalRequest request) {
            leaseIdentity(request);
            request.setSequence(++sequence);
            request.setEventId("daemon-terminal-" + UUID.randomUUID());
            request.setOccurredAt(now());
        }

        private String redact(String value) {
            if (value == null || StringUtils.isBlank(claim.getTaskScopedToken())) {
                return value;
            }
            return value.replace(claim.getTaskScopedToken(), "[REDACTED]");
        }

        private Map<String, Object> redactMap(Map<String, Object> values) {
            if (values == null || values.isEmpty()) {
                return values;
            }
            LinkedHashMap<String, Object> redacted = new LinkedHashMap<>();
            values.forEach((key, value) -> redacted.put(key, redactValue(value)));
            return redacted;
        }

        private Object redactValue(Object value) {
            if (value instanceof String text) {
                return redact(text);
            }
            if (value instanceof Map<?, ?> map) {
                LinkedHashMap<String, Object> redacted = new LinkedHashMap<>();
                map.forEach((key, item) -> redacted.put(redact(String.valueOf(key)), redactValue(item)));
                return redacted;
            }
            if (value instanceof Iterable<?> iterable) {
                ArrayList<Object> redacted = new ArrayList<>();
                iterable.forEach(item -> redacted.add(redactValue(item)));
                return redacted;
            }
            return value;
        }

        private void leaseIdentity(AgentRuntimeLeaseRenewRequest request) {
            request.setDaemonId(daemonId);
            request.setLeaseAttempt(claim.getLeaseAttempt());
            request.setExpectedLeaseRevision(revision);
        }

        private void update(AgentRuntimeLeaseStatus status) {
            if (status == null || status.getLeaseRevision() == null) {
                throw new ControlPlaneException("Runtime lease response omitted revision");
            }
            revision = status.getLeaseRevision();
            if (status.getLeaseExpiresAt() != null) {
                leaseExpiresAtMillis = status.getLeaseExpiresAt().getTime();
            }
            if (status.getRunStatus() != null) {
                runStatus.set(status.getRunStatus());
            }
            approvalDecisionPending.set(Boolean.TRUE.equals(status.getApprovalDecisionPending()));
            sqlContinuationAvailable.set(Boolean.TRUE.equals(status.getSqlContinuationAvailable()));
        }

        private boolean waitingForApproval() {
            return approvalDecisionPending.get();
        }

        private synchronized boolean expired() {
            return clock.millis() >= leaseExpiresAtMillis;
        }

        private Date now() {
            return Date.from(clock.instant());
        }
    }
}

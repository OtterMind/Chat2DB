package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApprovalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeEventAccepted;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeLeaseStatus;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeOption;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunClaim;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunTerminalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeTaskScope;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeHeartbeatRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeArtifactUploadRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeEventRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeLeaseRenewRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeProfileCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunClaimRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCompleteRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunFailRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCancelAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunStartedRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunSuspendRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeControlService;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeDispatchService;
import ai.chat2db.community.domain.api.service.ai.IAiToolService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeControllerTest {

    @Test
    void profileControllerBindsCurrentIdentityAndFiltersOwnedProfiles() {
        AtomicReference<AgentRuntimeProfileCreateRequest> captured = new AtomicReference<>();
        AgentRuntimeProfile mine = profile("mine", 7L);
        AgentRuntimeProfile other = profile("other", 8L);
        IAgentRuntimeControlService service = proxy(IAgentRuntimeControlService.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "createProfile" -> {
                    captured.set((AgentRuntimeProfileCreateRequest) args[0]);
                    yield mine;
                }
                case "listProfiles" -> List.of(mine, other);
                case "getProfile" -> other;
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        AgentRuntimeProfileController controller = new AgentRuntimeProfileController(service, () -> 7L);
        AgentRuntimeProfileCreateRequest request = new AgentRuntimeProfileCreateRequest();
        request.setCreatedBy(999L);

        controller.createProfile(request);

        assertEquals(7L, captured.get().getCreatedBy());
        assertEquals(List.of("mine"), controller.listProfiles().getData().stream()
                .map(AgentRuntimeProfile::getId).toList());
        assertThrows(SecurityException.class, () -> controller.getProfile("other"));
    }

    @Test
    void profileControllerListsRuntimeOptionsForCurrentIdentity() {
        AgentRuntimeOption option = new AgentRuntimeOption();
        option.setProfileId("profile-1");
        AtomicReference<Long> capturedOwner = new AtomicReference<>();
        IAgentRuntimeControlService service = proxy(IAgentRuntimeControlService.class, (proxy, method, args) -> {
            if ("listRuntimeOptions".equals(method.getName())) {
                capturedOwner.set((Long) args[0]);
                return List.of(option);
            }
            throw new UnsupportedOperationException(method.getName());
        });
        AgentRuntimeProfileController controller = new AgentRuntimeProfileController(service, () -> 7L);

        assertEquals(List.of("profile-1"), controller.listRuntimeOptions().getData().stream()
                .map(AgentRuntimeOption::getProfileId).toList());
        assertEquals(7L, capturedOwner.get());
    }

    @Test
    void daemonControllerPassesInstanceIdentityToHeartbeatService() {
        AtomicReference<String> capturedInstanceId = new AtomicReference<>();
        AtomicReference<AgentRuntimeHeartbeatRequest> capturedRequest = new AtomicReference<>();
        AgentRuntimeInstance instance = new AgentRuntimeInstance();
        instance.setId("instance-1");
        IAgentRuntimeControlService service = proxy(IAgentRuntimeControlService.class, (proxy, method, args) -> {
            if ("heartbeat".equals(method.getName())) {
                capturedInstanceId.set((String) args[0]);
                capturedRequest.set((AgentRuntimeHeartbeatRequest) args[1]);
                return instance;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        IAgentRuntimeDispatchService dispatchService = proxy(IAgentRuntimeDispatchService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        AgentRuntimeDaemonController controller = new AgentRuntimeDaemonController(
                service, dispatchService, unsupportedAiToolService());
        AgentRuntimeHeartbeatRequest request = new AgentRuntimeHeartbeatRequest();

        controller.heartbeat("instance-1", request);

        assertEquals("instance-1", capturedInstanceId.get());
        assertEquals(request, capturedRequest.get());
    }

    @Test
    void daemonControllerPassesClaimAndLeaseCredentialsToDispatchService() {
        IAgentRuntimeControlService controlService = proxy(IAgentRuntimeControlService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        AtomicReference<String> capturedInstanceId = new AtomicReference<>();
        AtomicReference<String> capturedRunId = new AtomicReference<>();
        AtomicReference<String> capturedLeaseToken = new AtomicReference<>();
        IAgentRuntimeDispatchService dispatchService = proxy(IAgentRuntimeDispatchService.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "claim" -> {
                        capturedInstanceId.set((String) args[0]);
                        yield new AgentRuntimeRunClaim();
                    }
                    case "renewLease", "markStarted", "suspendForSqlApproval" -> {
                        capturedRunId.set((String) args[0]);
                        capturedLeaseToken.set((String) args[1]);
                        yield new AgentRuntimeLeaseStatus();
                    }
                    case "appendEvent" -> {
                        capturedRunId.set((String) args[0]);
                        capturedLeaseToken.set((String) args[1]);
                        yield new AgentRuntimeEventAccepted();
                    }
                    case "requestApproval", "getApprovalStatus", "acknowledgeApproval" -> {
                        capturedRunId.set((String) args[0]);
                        capturedLeaseToken.set((String) args[1]);
                        yield new AgentRuntimeApprovalResult();
                    }
                    case "uploadArtifact" -> {
                        capturedRunId.set((String) args[0]);
                        capturedLeaseToken.set((String) args[1]);
                        yield new AgentRuntimeArtifactResult();
                    }
                    case "complete", "fail", "acknowledgeCancellation" -> {
                        capturedRunId.set((String) args[0]);
                        capturedLeaseToken.set((String) args[1]);
                        yield new AgentRuntimeRunTerminalResult();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AgentRuntimeDaemonController controller = new AgentRuntimeDaemonController(
                controlService, dispatchService, unsupportedAiToolService());

        controller.claim("instance-1", new AgentRuntimeRunClaimRequest());
        controller.renewLease("run-1", "lease-secret", new AgentRuntimeLeaseRenewRequest());
        controller.markStarted("run-1", "lease-secret", new AgentRuntimeRunStartedRequest());
        controller.suspendForSqlApproval("run-1", "lease-secret", new AgentRuntimeRunSuspendRequest());
        controller.appendEvent("run-1", "lease-secret", new AgentRuntimeEventRequest());
        controller.uploadArtifact("run-1", "lease-secret", new AgentRuntimeArtifactUploadRequest());
        controller.requestApproval("run-1", "lease-secret", new AgentRuntimeApprovalRequest());
        controller.approvalStatus("run-1", "lease-secret", new AgentRuntimeApprovalAckRequest());
        controller.acknowledgeApproval("run-1", "lease-secret", new AgentRuntimeApprovalAckRequest());
        controller.complete("run-1", "lease-secret", new AgentRuntimeRunCompleteRequest());
        controller.fail("run-1", "lease-secret", new AgentRuntimeRunFailRequest());
        controller.acknowledgeCancellation("run-1", "lease-secret", new AgentRuntimeRunCancelAckRequest());

        assertEquals("instance-1", capturedInstanceId.get());
        assertEquals("run-1", capturedRunId.get());
        assertEquals("lease-secret", capturedLeaseToken.get());
    }

    @Test
    void daemonToolControllerReplacesUntrustedContextWithAuthorizedRun() {
        IAgentRuntimeControlService controlService = proxy(IAgentRuntimeControlService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        AtomicReference<String> capturedRunId = new AtomicReference<>();
        AtomicReference<String> capturedTaskToken = new AtomicReference<>();
        IAgentRuntimeDispatchService dispatchService = proxy(IAgentRuntimeDispatchService.class,
                (proxy, method, args) -> {
                    if (!"authorizeTaskToken".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    capturedRunId.set((String) args[0]);
                    capturedTaskToken.set((String) args[1]);
                    AgentRuntimeTaskScope scope = new AgentRuntimeTaskScope();
                    scope.setRunId("authorized-run");
                    scope.setDataScopes(List.of());
                    return scope;
                });
        AtomicReference<AiExecuteSqlRequest> capturedSql = new AtomicReference<>();
        IAiToolService aiToolService = proxy(IAiToolService.class, (proxy, method, args) -> {
            if (!"executeSql".equals(method.getName())) {
                throw new UnsupportedOperationException(method.getName());
            }
            capturedSql.set((AiExecuteSqlRequest) args[0]);
            return "ok";
        });
        AgentRuntimeDaemonController controller = new AgentRuntimeDaemonController(
                controlService, dispatchService, aiToolService);
        AiExecuteSqlRequest request = new AiExecuteSqlRequest();
        request.setSql("select 1");
        ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest untrusted =
                new ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest();
        untrusted.setAgentRunId("spoofed-run");
        request.setAiToolContextRequest(untrusted);

        assertEquals("ok", controller.executeSql("path-run", "task-secret", request).getData());

        assertEquals("path-run", capturedRunId.get());
        assertEquals("task-secret", capturedTaskToken.get());
        assertEquals("authorized-run", capturedSql.get().getAiToolContextRequest().getAgentRunId());
        assertFalse(capturedSql.get().getAiToolContextRequest().getWaitForApprovalDecision());
    }

    private AgentRuntimeProfile profile(String id, Long createdBy) {
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setId(id);
        profile.setCreatedBy(createdBy);
        return profile;
    }

    private IAiToolService unsupportedAiToolService() {
        return proxy(IAiToolService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, handler);
    }
}

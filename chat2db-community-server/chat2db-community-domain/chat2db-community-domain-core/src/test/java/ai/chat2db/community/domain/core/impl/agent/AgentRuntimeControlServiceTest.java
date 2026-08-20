package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTransportEnum;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeOption;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunLease;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeHeartbeatRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeInstanceRegisterRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeProfileCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeProfileUpdateRequest;
import ai.chat2db.community.domain.api.service.storage.IAgentRuntimeControlStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeControlServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");

    private MemoryRuntimeStorage storage;
    private AgentRuntimeControlServiceImpl service;

    @BeforeEach
    void setUp() {
        storage = new MemoryRuntimeStorage();
        service = serviceAt(NOW);
    }

    @Test
    void createsCodexProfileWithSeparatedTransportAndProvider() {
        AgentRuntimeProfileCreateRequest request = codexProfile();
        request.setCreatedBy(7L);

        AgentRuntimeProfile profile = service.createProfile(request);

        assertNotNull(profile.getId());
        assertEquals(AgentRuntimeTransportEnum.EXTERNAL_DAEMON, profile.getTransport());
        assertEquals(AgentRuntimeProviderEnum.CODEX, profile.getProvider());
        assertEquals("codex", profile.getExecutable());
        assertEquals("TASK_ISOLATED", profile.getWorkingDirectoryPolicy());
        assertEquals(900, profile.getTimeoutSeconds());
        assertEquals(1, profile.getMaxConcurrency());
        assertEquals(7L, profile.getCreatedBy());
    }

    @Test
    void rejectsUnsafeHermesProfileWithoutApprovalBridge() {
        AgentRuntimeProfileCreateRequest request = codexProfile();
        request.setName("Hermes");
        request.setProvider(AgentRuntimeProviderEnum.HERMES);
        request.setApprovalBridgeEnabled(false);

        assertThrows(IllegalArgumentException.class, () -> service.createProfile(request));
    }

    @Test
    void createsDshProfileWithExplicitExecutableAndApprovalPolicy() {
        AgentRuntimeProfileCreateRequest request = codexProfile();
        request.setName("DeepSeek Harness");
        request.setProvider(AgentRuntimeProviderEnum.DSH);
        request.setApprovalBridgeEnabled(true);

        AgentRuntimeProfile profile = service.createProfile(request);

        assertEquals(AgentRuntimeProviderEnum.DSH, profile.getProvider());
        assertEquals("dsh", profile.getExecutable());
        assertTrue(profile.getApprovalBridgeEnabled());
    }

    @Test
    void updatesProfileWithOptimisticRevision() {
        AgentRuntimeProfile created = service.createProfile(codexProfile());
        AgentRuntimeProfileUpdateRequest update = new AgentRuntimeProfileUpdateRequest();
        update.setProfileId(created.getId());
        update.setExpectedRevision(created.getRevision());
        update.setName("Codex high capacity");
        update.setTransport(AgentRuntimeTransportEnum.EXTERNAL_DAEMON);
        update.setProvider(AgentRuntimeProviderEnum.CODEX);
        update.setMaxConcurrency(3);

        AgentRuntimeProfile updated = service.updateProfile(update);

        assertEquals(3, updated.getMaxConcurrency());
        assertEquals(2L, updated.getRevision());
        assertThrows(ConcurrentModificationException.class, () -> service.updateProfile(update));
    }

    @Test
    void registersIdempotentlyAndHeartbeatsWithoutOverwritingSchedulerCapacity() {
        AgentRuntimeInstanceRegisterRequest registration = codexRegistration();

        AgentRuntimeInstance created = service.register(registration);
        created.setActiveRuns(1);
        AgentRuntimeInstance registeredAgain = service.register(registration);

        assertEquals(created.getId(), registeredAgain.getId());
        assertEquals(2L, registeredAgain.getRevision());
        AgentRuntimeHeartbeatRequest heartbeat = new AgentRuntimeHeartbeatRequest();
        heartbeat.setDaemonId("daemon-local");
        heartbeat.setActiveRuns(0);
        heartbeat.setStatus(AgentRuntimeInstanceStatusEnum.DEGRADED);
        heartbeat.setExpectedRevision(1L);
        AgentRuntimeInstance updated = service.heartbeat(registeredAgain.getId(), heartbeat);
        assertEquals(AgentRuntimeInstanceStatusEnum.DEGRADED, updated.getStatus());
        assertEquals(1, updated.getActiveRuns());
        assertEquals(3L, updated.getRevision());
    }

    @Test
    void reportsInstanceOfflineAfterHeartbeatTimeoutWithoutMutatingStoredState() {
        AgentRuntimeInstance created = service.register(codexRegistration());

        AgentRuntimeControlServiceImpl later = serviceAt(NOW.plusSeconds(91));
        AgentRuntimeInstance effective = later.getInstance(created.getId());

        assertEquals(AgentRuntimeInstanceStatusEnum.OFFLINE, effective.getStatus());
        assertEquals(AgentRuntimeInstanceStatusEnum.ONLINE, storage.getRuntimeInstance(created.getId()).getStatus());
    }

    @Test
    void createsOneDefaultRuntimeProfileAndReturnsDetectedRuntimeStatus() {
        service.register(codexRegistration());

        List<AgentRuntimeOption> first = service.listRuntimeOptions(7L);
        List<AgentRuntimeOption> second = service.listRuntimeOptions(7L);

        assertEquals(1, first.size());
        assertEquals(first.get(0).getProfileId(), second.get(0).getProfileId());
        assertEquals(1, storage.listRuntimeProfiles().size());
        AgentRuntimeOption option = first.get(0);
        assertEquals(AgentRuntimeProviderEnum.CODEX, option.getProvider());
        assertEquals("1.2.3", option.getProviderVersion());
        assertEquals("codex", option.getExecutable());
        assertTrue(option.getDefaultProfile());
        assertTrue(option.getInstalled());
        assertTrue(option.getOnline());
    }

    @Test
    void createsDefaultProfilesForEverySupportedExternalCli() {
        List<AgentRuntimeProviderEnum> providers = List.of(
                AgentRuntimeProviderEnum.CLAUDE_CODE,
                AgentRuntimeProviderEnum.OPENCODE,
                AgentRuntimeProviderEnum.PI);
        for (AgentRuntimeProviderEnum provider : providers) {
            AgentRuntimeInstanceRegisterRequest request = codexRegistration();
            request.setProvider(provider);
            service.register(request);
        }

        List<AgentRuntimeOption> options = service.listRuntimeOptions(7L);

        assertEquals(providers, options.stream().map(AgentRuntimeOption::getProvider).toList());
        assertEquals(List.of("claude", "opencode", "pi"),
                options.stream().map(AgentRuntimeOption::getExecutable).toList());
        assertTrue(options.stream().allMatch(AgentRuntimeOption::getDefaultProfile));
    }

    @Test
    void returnsDetectedRuntimeAsOfflineAfterHeartbeatTimeout() {
        service.register(codexRegistration());

        AgentRuntimeOption option = serviceAt(NOW.plusSeconds(91)).listRuntimeOptions(7L).get(0);

        assertEquals(AgentRuntimeInstanceStatusEnum.OFFLINE, option.getStatus());
        assertTrue(option.getInstalled());
        assertFalse(option.getOnline());
    }

    @Test
    void rejectsHeartbeatThatExceedsRegisteredCapacity() {
        AgentRuntimeInstance created = service.register(codexRegistration());
        AgentRuntimeHeartbeatRequest heartbeat = new AgentRuntimeHeartbeatRequest();
        heartbeat.setDaemonId(created.getDaemonId());
        heartbeat.setActiveRuns(3);
        heartbeat.setExpectedRevision(created.getRevision());

        assertThrows(IllegalArgumentException.class, () -> service.heartbeat(created.getId(), heartbeat));
    }

    private AgentRuntimeControlServiceImpl serviceAt(Instant instant) {
        return new AgentRuntimeControlServiceImpl(storage, Clock.fixed(instant, ZoneOffset.UTC), 90_000L);
    }

    private AgentRuntimeProfileCreateRequest codexProfile() {
        AgentRuntimeProfileCreateRequest request = new AgentRuntimeProfileCreateRequest();
        request.setName("Codex local");
        request.setTransport(AgentRuntimeTransportEnum.EXTERNAL_DAEMON);
        request.setProvider(AgentRuntimeProviderEnum.CODEX);
        request.setEnvironmentReferences(Map.of("OPENAI_API_KEY", "secret:openai"));
        request.setMcpConfiguration("{}");
        request.setSessionResumeEnabled(true);
        return request;
    }

    private AgentRuntimeInstanceRegisterRequest codexRegistration() {
        AgentRuntimeInstanceRegisterRequest request = new AgentRuntimeInstanceRegisterRequest();
        request.setDaemonId("daemon-local");
        request.setProvider(AgentRuntimeProviderEnum.CODEX);
        request.setProviderVersion("1.2.3");
        request.setProtocolVersion("1");
        request.setCapabilities(java.util.Set.of("STREAMING", "SESSION_RESUME"));
        request.setMaxConcurrency(2);
        return request;
    }

    private static class MemoryRuntimeStorage implements IAgentRuntimeControlStorage {

        private final Map<String, AgentRuntimeProfile> profiles = new LinkedHashMap<>();
        private final Map<String, AgentRuntimeInstance> instances = new LinkedHashMap<>();

        @Override
        public AgentRuntimeProfile createRuntimeProfile(AgentRuntimeProfile profile) {
            profiles.put(profile.getId(), profile);
            return profile;
        }

        @Override
        public AgentRuntimeProfile updateRuntimeProfile(AgentRuntimeProfile profile, long expectedRevision) {
            AgentRuntimeProfile current = profiles.get(profile.getId());
            if (current == null || current.getRevision() != expectedRevision) {
                throw new ConcurrentModificationException();
            }
            profiles.put(profile.getId(), profile);
            return profile;
        }

        @Override
        public AgentRuntimeProfile getRuntimeProfile(String id) {
            return profiles.get(id);
        }

        @Override
        public List<AgentRuntimeProfile> listRuntimeProfiles() {
            return new ArrayList<>(profiles.values());
        }

        @Override
        public AgentRuntimeInstance createRuntimeInstance(AgentRuntimeInstance instance) {
            instances.put(instance.getId(), instance);
            return instance;
        }

        @Override
        public AgentRuntimeInstance updateRuntimeInstance(AgentRuntimeInstance instance, long expectedRevision) {
            AgentRuntimeInstance current = instances.get(instance.getId());
            if (current == null || current.getRevision() != expectedRevision) {
                throw new ConcurrentModificationException();
            }
            instances.put(instance.getId(), instance);
            return instance;
        }

        @Override
        public AgentRuntimeInstance heartbeatRuntimeInstance(String instanceId, String daemonId,
                                                              AgentRuntimeInstanceStatusEnum status,
                                                              java.util.Date heartbeatAt) {
            AgentRuntimeInstance current = instances.get(instanceId);
            if (current == null || !current.getDaemonId().equals(daemonId)
                    || current.getStatus() == AgentRuntimeInstanceStatusEnum.DISABLED) {
                throw new ConcurrentModificationException();
            }
            current.setStatus(status);
            current.setLastHeartbeatAt(heartbeatAt);
            current.setGmtModified(heartbeatAt);
            current.setRevision(current.getRevision() + 1);
            return current;
        }

        @Override
        public AgentRuntimeInstance getRuntimeInstance(String id) {
            return instances.get(id);
        }

        @Override
        public AgentRuntimeInstance findRuntimeInstance(String daemonId, AgentRuntimeProviderEnum provider) {
            return instances.values().stream()
                    .filter(instance -> instance.getDaemonId().equals(daemonId) && instance.getProvider() == provider)
                    .findFirst().orElse(null);
        }

        @Override
        public List<AgentRuntimeInstance> listRuntimeInstances() {
            return new ArrayList<>(instances.values());
        }

        @Override
        public AgentRuntimeRunLease claimRuntimeRun(String instanceId, AgentRuntimeProviderEnum provider,
                                                    String leaseTokenHash, String taskTokenHash,
                                                    java.util.Date claimedAt, java.util.Date leaseExpiresAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeRunLease getRuntimeRunLease(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeRunLease updateRuntimeRunLease(AgentRuntimeRunLease lease, long expectedRevision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeRunLease startRuntimeRun(AgentRuntimeRunLease lease, long expectedLeaseRevision,
                                                    long expectedRunRevision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRunEvent appendRuntimeRunEvent(AgentRunEvent event, int leaseAttempt,
                                                   long runtimeSequence, java.util.Date acceptedAt,
                                                   String providerSessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeRunLease requestRuntimeRunCancellation(String runId, java.util.Date requestedAt) {
            throw new UnsupportedOperationException();
        }

        @Override public ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval createOrGetRuntimeApproval(
                ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval approval) { throw new UnsupportedOperationException(); }
        @Override public ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval getRuntimeApproval(String id) { throw new UnsupportedOperationException(); }
        @Override public ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval findRuntimeApproval(String runId, int attempt, String requestId) { throw new UnsupportedOperationException(); }
        @Override public List<ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval> listRuntimeApprovals(String runId) { throw new UnsupportedOperationException(); }
        @Override public ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval updateRuntimeApproval(
                ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval approval, long revision) { throw new UnsupportedOperationException(); }

        @Override
        public AgentRuntimeRunLease finishRuntimeRun(AgentRuntimeRunLease lease, AgentRunEvent terminalEvent,
                                                     ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum targetStatus,
                                                     String failureReason, String resultSummary,
                                                     java.util.Date completedAt, long expectedLeaseRevision,
                                                     long expectedRunRevision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeRunLease suspendRuntimeRun(AgentRuntimeRunLease lease, AgentRunEvent suspendEvent,
                                                       ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum targetRunStatus,
                                                       java.util.Date suspendedAt, long expectedLeaseRevision,
                                                       long expectedRunRevision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> reconcileExpiredRuntimeRuns(java.util.Date expiredAt, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}

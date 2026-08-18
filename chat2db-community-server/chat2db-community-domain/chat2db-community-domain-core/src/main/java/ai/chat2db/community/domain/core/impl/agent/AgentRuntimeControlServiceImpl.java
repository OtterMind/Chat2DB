package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTransportEnum;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeOption;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeHeartbeatRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeInstanceRegisterRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeProfileCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeProfileUpdateRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeControlService;
import ai.chat2db.community.domain.api.service.storage.IAgentRuntimeControlStorage;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.Comparator;
import java.util.Objects;

@Service
public class AgentRuntimeControlServiceImpl implements IAgentRuntimeControlService {

    static final long DEFAULT_HEARTBEAT_TIMEOUT_MILLIS = 90_000L;
    private static final int DEFAULT_TIMEOUT_SECONDS = 900;
    private static final int DEFAULT_MAX_CONCURRENCY = 1;
    private static final String DEFAULT_WORKING_DIRECTORY_POLICY = "TASK_ISOLATED";

    private final IAgentRuntimeControlStorage storage;
    private final Clock clock;
    private final long heartbeatTimeoutMillis;

    @Autowired
    public AgentRuntimeControlServiceImpl(IAgentRuntimeControlStorage storage) {
        this(storage, Clock.systemUTC(), DEFAULT_HEARTBEAT_TIMEOUT_MILLIS);
    }

    AgentRuntimeControlServiceImpl(IAgentRuntimeControlStorage storage, Clock clock, long heartbeatTimeoutMillis) {
        this.storage = storage;
        this.clock = clock;
        this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
    }

    @Override
    public AgentRuntimeProfile createProfile(AgentRuntimeProfileCreateRequest request) {
        validateProfileRequest(request);
        Date now = now();
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setId(UUID.randomUUID().toString());
        applyProfile(profile, request);
        profile.setCreatedBy(request.getCreatedBy());
        profile.setGmtCreate(now);
        profile.setGmtModified(now);
        profile.setRevision(1L);
        return storage.createRuntimeProfile(profile);
    }

    @Override
    public AgentRuntimeProfile updateProfile(AgentRuntimeProfileUpdateRequest request) {
        if (request == null || StringUtils.isBlank(request.getProfileId())) {
            throw new IllegalArgumentException("runtime profile update request is required");
        }
        if (request.getExpectedRevision() == null || request.getExpectedRevision() <= 0) {
            throw new IllegalArgumentException("positive expected runtime profile revision is required");
        }
        validateProfileRequest(request);
        AgentRuntimeProfile current = getProfile(request.getProfileId());
        if (!current.getRevision().equals(request.getExpectedRevision())) {
            throw new ConcurrentModificationException("runtime profile revision has changed: " + current.getId());
        }
        AgentRuntimeProfile updated = copyProfile(current);
        applyProfile(updated, request);
        updated.setGmtModified(now());
        updated.setRevision(current.getRevision() + 1);
        return storage.updateRuntimeProfile(updated, request.getExpectedRevision());
    }

    @Override
    public AgentRuntimeProfile getProfile(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("runtime profile id is required");
        }
        AgentRuntimeProfile profile = storage.getRuntimeProfile(id);
        if (profile == null) {
            throw new NoSuchElementException("runtime profile not found: " + id);
        }
        return profile;
    }

    @Override
    public List<AgentRuntimeProfile> listProfiles() {
        return storage.listRuntimeProfiles();
    }

    @Override
    public AgentRuntimeInstance register(AgentRuntimeInstanceRegisterRequest request) {
        validateRegistration(request);
        AgentRuntimeInstance current = storage.findRuntimeInstance(request.getDaemonId().trim(), request.getProvider());
        Date now = now();
        if (current == null) {
            AgentRuntimeInstance created = new AgentRuntimeInstance();
            created.setId(UUID.randomUUID().toString());
            created.setDaemonId(request.getDaemonId().trim());
            created.setProvider(request.getProvider());
            created.setRegisteredAt(now);
            created.setRevision(1L);
            applyRegistration(created, request, now);
            return storage.createRuntimeInstance(created);
        }
        if (current.getStatus() == AgentRuntimeInstanceStatusEnum.DISABLED) {
            throw new IllegalStateException("runtime instance is disabled: " + current.getId());
        }
        AgentRuntimeInstance updated = copyInstance(current);
        applyRegistration(updated, request, now);
        updated.setRevision(current.getRevision() + 1);
        return storage.updateRuntimeInstance(updated, current.getRevision());
    }

    @Override
    public AgentRuntimeInstance heartbeat(String instanceId, AgentRuntimeHeartbeatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("runtime heartbeat request is required");
        }
        if (StringUtils.isBlank(request.getDaemonId())) {
            throw new IllegalArgumentException("runtime daemon id is required");
        }
        AgentRuntimeInstance current = getInstance(instanceId);
        if (!current.getDaemonId().equals(request.getDaemonId().trim())) {
            throw new IllegalArgumentException("runtime daemon id does not match registered instance");
        }
        if (current.getStatus() == AgentRuntimeInstanceStatusEnum.DISABLED) {
            throw new IllegalStateException("runtime instance is disabled: " + instanceId);
        }
        int activeRuns = request.getActiveRuns() == null ? 0 : request.getActiveRuns();
        if (activeRuns < 0 || activeRuns > current.getMaxConcurrency()) {
            throw new IllegalArgumentException("runtime active runs must be between zero and max concurrency");
        }
        AgentRuntimeInstanceStatusEnum status = request.getStatus() == null
                ? AgentRuntimeInstanceStatusEnum.ONLINE
                : request.getStatus();
        if (status != AgentRuntimeInstanceStatusEnum.ONLINE
                && status != AgentRuntimeInstanceStatusEnum.DEGRADED) {
            throw new IllegalArgumentException("runtime heartbeat status must be ONLINE or DEGRADED");
        }
        Date now = now();
        // activeRuns is scheduler-owned state. The daemon value is telemetry only and must not
        // overwrite slots reserved or released concurrently by claim/terminal transactions.
        return storage.heartbeatRuntimeInstance(instanceId, current.getDaemonId(), status, now);
    }

    @Override
    public AgentRuntimeInstance getInstance(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("runtime instance id is required");
        }
        AgentRuntimeInstance instance = storage.getRuntimeInstance(id);
        if (instance == null) {
            throw new NoSuchElementException("runtime instance not found: " + id);
        }
        return withEffectiveStatus(instance);
    }

    @Override
    public List<AgentRuntimeInstance> listInstances() {
        return storage.listRuntimeInstances().stream().map(this::withEffectiveStatus).toList();
    }

    @Override
    public List<AgentRuntimeOption> listRuntimeOptions(Long ownerId) {
        if (ownerId == null) {
            throw new IllegalArgumentException("runtime option owner is required");
        }
        List<AgentRuntimeInstance> instances = listInstances();
        for (AgentRuntimeProviderEnum provider : List.of(
                AgentRuntimeProviderEnum.CODEX, AgentRuntimeProviderEnum.HERMES,
                AgentRuntimeProviderEnum.DSH)) {
            if (instances.stream().anyMatch(instance -> instance.getProvider() == provider)) {
                ensureDefaultProfile(ownerId, provider);
            }
        }

        List<AgentRuntimeProfile> profiles = storage.listRuntimeProfiles().stream()
                .filter(profile -> Objects.equals(profile.getCreatedBy(), ownerId))
                .filter(profile -> Boolean.TRUE.equals(profile.getEnabled()))
                .filter(profile -> profile.getTransport() == AgentRuntimeTransportEnum.EXTERNAL_DAEMON)
                .filter(profile -> profile.getProvider() != AgentRuntimeProviderEnum.SPRING_AI)
                .sorted(Comparator
                        .comparing((AgentRuntimeProfile profile) -> !isDefaultProfile(profile, ownerId))
                        .thenComparing(profile -> profile.getProvider().name())
                        .thenComparing(AgentRuntimeProfile::getName))
                .toList();
        return profiles.stream().map(profile -> runtimeOption(profile, instances, ownerId)).toList();
    }

    private synchronized AgentRuntimeProfile ensureDefaultProfile(Long ownerId, AgentRuntimeProviderEnum provider) {
        String expectedName = defaultProfileName(provider, ownerId);
        AgentRuntimeProfile existing = storage.listRuntimeProfiles().stream()
                .filter(profile -> Objects.equals(profile.getCreatedBy(), ownerId))
                .filter(profile -> profile.getProvider() == provider)
                .filter(profile -> expectedName.equals(profile.getName()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        AgentRuntimeProfileCreateRequest request = new AgentRuntimeProfileCreateRequest();
        request.setName(expectedName);
        request.setTransport(AgentRuntimeTransportEnum.EXTERNAL_DAEMON);
        request.setProvider(provider);
        request.setExecutable(provider.defaultExecutable());
        request.setSessionResumeEnabled(true);
        request.setApprovalBridgeEnabled(provider.requiresApprovalBridge());
        request.setEnabled(true);
        request.setCreatedBy(ownerId);
        return createProfile(request);
    }

    private AgentRuntimeOption runtimeOption(AgentRuntimeProfile profile,
                                             List<AgentRuntimeInstance> instances,
                                             Long ownerId) {
        AgentRuntimeInstance best = instances.stream()
                .filter(instance -> instance.getProvider() == profile.getProvider())
                .sorted(Comparator
                        .comparing((AgentRuntimeInstance instance) -> !isOnline(instance))
                        .thenComparing(AgentRuntimeInstance::getLastHeartbeatAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);
        AgentRuntimeOption option = new AgentRuntimeOption();
        option.setProfileId(profile.getId());
        option.setProfileName(profile.getName());
        option.setProvider(profile.getProvider());
        option.setExecutable(profile.getExecutable());
        option.setDefaultProfile(isDefaultProfile(profile, ownerId));
        option.setInstalled(best != null);
        option.setOnline(best != null && isOnline(best));
        if (best != null) {
            option.setStatus(best.getStatus());
            option.setProviderVersion(best.getProviderVersion());
            option.setDaemonId(best.getDaemonId());
            option.setActiveRuns(best.getActiveRuns());
            option.setMaxConcurrency(best.getMaxConcurrency());
        }
        return option;
    }

    private boolean isOnline(AgentRuntimeInstance instance) {
        return instance.getStatus() == AgentRuntimeInstanceStatusEnum.ONLINE
                || instance.getStatus() == AgentRuntimeInstanceStatusEnum.DEGRADED;
    }

    private boolean isDefaultProfile(AgentRuntimeProfile profile, Long ownerId) {
        return profile.getName().equals(defaultProfileName(profile.getProvider(), ownerId));
    }

    private String defaultProfileName(AgentRuntimeProviderEnum provider, Long ownerId) {
        return "Chat2DB Local " + provider.displayName() + " (" + ownerId + ")";
    }

    private void validateProfileRequest(AgentRuntimeProfileCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("runtime profile request is required");
        }
        if (StringUtils.isBlank(request.getName())) {
            throw new IllegalArgumentException("runtime profile name is required");
        }
        if (request.getName().trim().length() > 128) {
            throw new IllegalArgumentException("runtime profile name must not exceed 128 characters");
        }
        if (request.getTransport() == null || request.getProvider() == null) {
            throw new IllegalArgumentException("runtime transport and provider are required");
        }
        if (request.getProvider() == AgentRuntimeProviderEnum.SPRING_AI
                && request.getTransport() != AgentRuntimeTransportEnum.EMBEDDED) {
            throw new IllegalArgumentException("Spring AI runtime must use EMBEDDED transport");
        }
        if (request.getProvider() != AgentRuntimeProviderEnum.SPRING_AI
                && request.getTransport() != AgentRuntimeTransportEnum.EXTERNAL_DAEMON) {
            throw new IllegalArgumentException("External Agent runtimes must use EXTERNAL_DAEMON transport");
        }
        if (request.getTimeoutSeconds() != null && request.getTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("runtime timeout must be positive");
        }
        if (request.getMaxConcurrency() != null && request.getMaxConcurrency() <= 0) {
            throw new IllegalArgumentException("runtime max concurrency must be positive");
        }
        for (String argument : request.getCustomArguments() == null ? List.<String>of() : request.getCustomArguments()) {
            if (StringUtils.isBlank(argument) || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("runtime custom arguments must be non-blank single-line values");
            }
        }
        for (Map.Entry<String, String> entry : request.getEnvironmentReferences() == null
                ? Map.<String, String>of().entrySet()
                : request.getEnvironmentReferences().entrySet()) {
            if (StringUtils.isBlank(entry.getKey()) || StringUtils.isBlank(entry.getValue())) {
                throw new IllegalArgumentException("runtime environment references must have non-blank names and references");
            }
        }
        if (StringUtils.isNotBlank(request.getMcpConfiguration())) {
            try {
                JSON.parseObject(request.getMcpConfiguration());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("runtime MCP configuration must be valid JSON", exception);
            }
        }
        if (request.getProvider().requiresApprovalBridge()
                && !Boolean.TRUE.equals(request.getApprovalBridgeEnabled())) {
            throw new IllegalArgumentException(request.getProvider().displayName()
                    + " runtime requires the approval bridge");
        }
    }

    private void validateRegistration(AgentRuntimeInstanceRegisterRequest request) {
        if (request == null || StringUtils.isBlank(request.getDaemonId())) {
            throw new IllegalArgumentException("runtime daemon id is required");
        }
        if (request.getDaemonId().trim().length() > 128) {
            throw new IllegalArgumentException("runtime daemon id must not exceed 128 characters");
        }
        if (request.getProvider() == null || !request.getProvider().isExternal()) {
            throw new IllegalArgumentException("external runtime provider must be CODEX, HERMES, or DSH");
        }
        if (StringUtils.isBlank(request.getProviderVersion()) || StringUtils.isBlank(request.getProtocolVersion())) {
            throw new IllegalArgumentException("runtime provider and protocol versions are required");
        }
        if (request.getMaxConcurrency() == null || request.getMaxConcurrency() <= 0) {
            throw new IllegalArgumentException("runtime max concurrency must be positive");
        }
        if (request.getCapabilities() != null && request.getCapabilities().stream()
                .anyMatch(capability -> capability == null || StringUtils.isBlank(capability))) {
            throw new IllegalArgumentException("runtime capabilities must be non-blank");
        }
    }

    private void applyProfile(AgentRuntimeProfile profile, AgentRuntimeProfileCreateRequest request) {
        profile.setName(request.getName().trim());
        profile.setTransport(request.getTransport());
        profile.setProvider(request.getProvider());
        profile.setExecutable(StringUtils.defaultIfBlank(
                request.getExecutable(), request.getProvider().defaultExecutable()));
        profile.setModel(StringUtils.trimToNull(request.getModel()));
        profile.setWorkingDirectoryPolicy(StringUtils.defaultIfBlank(
                request.getWorkingDirectoryPolicy(), DEFAULT_WORKING_DIRECTORY_POLICY));
        profile.setCustomArguments(new ArrayList<>(request.getCustomArguments() == null
                ? List.of() : request.getCustomArguments()));
        profile.setEnvironmentReferences(new LinkedHashMap<>(request.getEnvironmentReferences() == null
                ? Map.of() : request.getEnvironmentReferences()));
        profile.setMcpConfiguration(StringUtils.trimToNull(request.getMcpConfiguration()));
        profile.setTimeoutSeconds(request.getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS : request.getTimeoutSeconds());
        profile.setMaxConcurrency(request.getMaxConcurrency() == null
                ? DEFAULT_MAX_CONCURRENCY : request.getMaxConcurrency());
        profile.setThinkingMode(StringUtils.trimToNull(request.getThinkingMode()));
        profile.setServiceTier(StringUtils.trimToNull(request.getServiceTier()));
        profile.setSessionResumeEnabled(Boolean.TRUE.equals(request.getSessionResumeEnabled()));
        profile.setApprovalBridgeEnabled(Boolean.TRUE.equals(request.getApprovalBridgeEnabled()));
        profile.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private void applyRegistration(AgentRuntimeInstance instance, AgentRuntimeInstanceRegisterRequest request, Date now) {
        instance.setProviderVersion(request.getProviderVersion().trim());
        instance.setProtocolVersion(request.getProtocolVersion().trim());
        instance.setCapabilities(new LinkedHashSet<>(request.getCapabilities() == null
                ? java.util.Set.of() : request.getCapabilities()));
        instance.setMaxConcurrency(request.getMaxConcurrency());
        if (instance.getActiveRuns() == null) {
            instance.setActiveRuns(0);
        }
        instance.setStatus(AgentRuntimeInstanceStatusEnum.ONLINE);
        instance.setLastHeartbeatAt(now);
        instance.setGmtModified(now);
    }

    private AgentRuntimeInstance withEffectiveStatus(AgentRuntimeInstance source) {
        AgentRuntimeInstance copy = copyInstance(source);
        if (copy.getStatus() != AgentRuntimeInstanceStatusEnum.DISABLED
                && copy.getLastHeartbeatAt() != null
                && now().getTime() - copy.getLastHeartbeatAt().getTime() > heartbeatTimeoutMillis) {
            copy.setStatus(AgentRuntimeInstanceStatusEnum.OFFLINE);
        }
        return copy;
    }

    private AgentRuntimeProfile copyProfile(AgentRuntimeProfile source) {
        AgentRuntimeProfile copy = new AgentRuntimeProfile();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setTransport(source.getTransport());
        copy.setProvider(source.getProvider());
        copy.setExecutable(source.getExecutable());
        copy.setModel(source.getModel());
        copy.setWorkingDirectoryPolicy(source.getWorkingDirectoryPolicy());
        copy.setCustomArguments(new ArrayList<>(source.getCustomArguments()));
        copy.setEnvironmentReferences(new LinkedHashMap<>(source.getEnvironmentReferences()));
        copy.setMcpConfiguration(source.getMcpConfiguration());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        copy.setMaxConcurrency(source.getMaxConcurrency());
        copy.setThinkingMode(source.getThinkingMode());
        copy.setServiceTier(source.getServiceTier());
        copy.setSessionResumeEnabled(source.getSessionResumeEnabled());
        copy.setApprovalBridgeEnabled(source.getApprovalBridgeEnabled());
        copy.setEnabled(source.getEnabled());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setGmtCreate(source.getGmtCreate());
        copy.setGmtModified(source.getGmtModified());
        copy.setRevision(source.getRevision());
        return copy;
    }

    private AgentRuntimeInstance copyInstance(AgentRuntimeInstance source) {
        AgentRuntimeInstance copy = new AgentRuntimeInstance();
        copy.setId(source.getId());
        copy.setDaemonId(source.getDaemonId());
        copy.setProvider(source.getProvider());
        copy.setProviderVersion(source.getProviderVersion());
        copy.setProtocolVersion(source.getProtocolVersion());
        copy.setCapabilities(new LinkedHashSet<>(source.getCapabilities()));
        copy.setMaxConcurrency(source.getMaxConcurrency());
        copy.setActiveRuns(source.getActiveRuns());
        copy.setStatus(source.getStatus());
        copy.setLastHeartbeatAt(source.getLastHeartbeatAt());
        copy.setRegisteredAt(source.getRegisteredAt());
        copy.setGmtModified(source.getGmtModified());
        copy.setRevision(source.getRevision());
        return copy;
    }

    private Date now() {
        return Date.from(clock.instant());
    }
}

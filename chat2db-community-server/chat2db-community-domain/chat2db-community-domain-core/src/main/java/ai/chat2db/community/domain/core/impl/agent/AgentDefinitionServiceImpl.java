package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionUpdateRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentDefinitionService;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.Map;

@Service
public class AgentDefinitionServiceImpl implements IAgentDefinitionService {

    private static final int DEFAULT_MAX_ROWS = 200;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private final IAgentControlStorage storage;

    public AgentDefinitionServiceImpl(IAgentControlStorage storage) {
        this.storage = storage;
    }

    @Override
    public AgentDefinition create(AgentDefinitionCreateRequest request) {
        validate(request);

        Date now = new Date();
        AgentDefinition agent = new AgentDefinition();
        agent.setId(UUID.randomUUID().toString());
        agent.setName(request.getName().trim());
        agent.setAvatar(StringUtils.trimToNull(request.getAvatar()));
        agent.setDescription(StringUtils.trimToNull(request.getDescription()));
        agent.setStatus(AgentStatusEnum.ACTIVE);
        agent.setRuntimeType(request.getRuntimeType() == null
                ? AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI
                : request.getRuntimeType());
        agent.setRuntimeProfileId(StringUtils.trimToNull(request.getRuntimeProfileId()));
        agent.setModelConfigId(StringUtils.trimToNull(request.getModelConfigId()));
        agent.setSystemPrompt(StringUtils.trimToNull(request.getSystemPrompt()));
        agent.setCapabilities(request.getCapabilities() == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(request.getCapabilities()));
        agent.setDataScopes(copyAndNormalizeScopes(request.getDataScopes()));
        agent.setOutputContract(StringUtils.trimToNull(request.getOutputContract()));
        agent.setCreatedBy(request.getCreatedBy());
        agent.setGmtCreate(now);
        agent.setGmtModified(now);
        agent.setRevision(1L);
        return storage.createAgent(agent);
    }

    @Override
    public AgentDefinition update(AgentDefinitionUpdateRequest request) {
        validate(request);
        if (request.getExpectedRevision() == null) {
            throw new IllegalArgumentException("agent expected revision is required");
        }
        AgentDefinition current = get(request.getAgentId());
        AgentDefinition updated = copy(current);
        applyDefinition(updated, request.getName(), request.getAvatar(), request.getDescription(), request.getStatus(),
                request.getRuntimeType(), request.getRuntimeProfileId(), request.getModelConfigId(),
                request.getSystemPrompt(), request.getCapabilities(), request.getDataScopes(), request.getOutputContract());
        updated.setGmtModified(new Date());
        updated.setRevision(current.getRevision() + 1);
        return storage.updateAgent(updated, request.getExpectedRevision());
    }

    @Override
    public AgentDefinition archive(String id, long expectedRevision) {
        AgentDefinition current = get(id);
        AgentDefinition updated = copy(current);
        updated.setStatus(AgentStatusEnum.ARCHIVED);
        updated.setGmtModified(new Date());
        updated.setRevision(current.getRevision() + 1);
        return storage.updateAgent(updated, expectedRevision);
    }

    @Override
    public AgentDefinition get(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("agent id is required");
        }
        AgentDefinition agent = storage.getAgent(id);
        if (agent == null) {
            throw new NoSuchElementException("agent not found: " + id);
        }
        return agent;
    }

    @Override
    public List<AgentDefinition> list() {
        return storage.listAgents();
    }

    private void validate(AgentDefinitionCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("agent request is required");
        }
        if (StringUtils.isBlank(request.getName())) {
            throw new IllegalArgumentException("agent name is required");
        }
        if (request.getName().trim().length() > 128) {
            throw new IllegalArgumentException("agent name must not exceed 128 characters");
        }
        if (request.getCapabilities() != null && request.getCapabilities().contains(null)) {
            throw new IllegalArgumentException("agent capabilities must not contain null");
        }
        for (AgentDataScope scope : request.getDataScopes() == null ? List.<AgentDataScope>of() : request.getDataScopes()) {
            validateScope(scope);
        }
        if (request.getRuntimeType() == AgentRuntimeTypeEnum.EXTERNAL_AGENT
                && StringUtils.isBlank(request.getRuntimeProfileId())) {
            throw new IllegalArgumentException("external agent runtime profile is required");
        }
        validateOutputContract(request.getOutputContract());
    }

    private void validate(AgentDefinitionUpdateRequest request) {
        if (request == null || StringUtils.isBlank(request.getAgentId())) {
            throw new IllegalArgumentException("agent update request is required");
        }
        validateDefinition(request.getName(), request.getRuntimeType(), request.getRuntimeProfileId(),
                request.getCapabilities(), request.getDataScopes(), request.getOutputContract());
    }

    private void validateDefinition(String name, AgentRuntimeTypeEnum runtimeType, String runtimeProfileId,
                                    java.util.Set<?> capabilities, List<AgentDataScope> dataScopes,
                                    String outputContract) {
        if (StringUtils.isBlank(name)) throw new IllegalArgumentException("agent name is required");
        if (name.trim().length() > 128) throw new IllegalArgumentException("agent name must not exceed 128 characters");
        if (capabilities != null && capabilities.contains(null)) {
            throw new IllegalArgumentException("agent capabilities must not contain null");
        }
        for (AgentDataScope scope : dataScopes == null ? List.<AgentDataScope>of() : dataScopes) validateScope(scope);
        if (runtimeType == AgentRuntimeTypeEnum.EXTERNAL_AGENT && StringUtils.isBlank(runtimeProfileId)) {
            throw new IllegalArgumentException("external agent runtime profile is required");
        }
        validateOutputContract(outputContract);
    }

    private void applyDefinition(AgentDefinition agent, String name, String avatar, String description,
                                 AgentStatusEnum status, AgentRuntimeTypeEnum runtimeType, String runtimeProfileId,
                                 String modelConfigId, String systemPrompt,
                                 java.util.Set<ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum> capabilities,
                                 List<AgentDataScope> dataScopes, String outputContract) {
        agent.setName(name.trim());
        agent.setAvatar(StringUtils.trimToNull(avatar));
        agent.setDescription(StringUtils.trimToNull(description));
        agent.setStatus(status == null ? AgentStatusEnum.ACTIVE : status);
        agent.setRuntimeType(runtimeType == null ? AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI : runtimeType);
        agent.setRuntimeProfileId(StringUtils.trimToNull(runtimeProfileId));
        agent.setModelConfigId(StringUtils.trimToNull(modelConfigId));
        agent.setSystemPrompt(StringUtils.trimToNull(systemPrompt));
        agent.setCapabilities(capabilities == null ? new LinkedHashSet<>() : new LinkedHashSet<>(capabilities));
        agent.setDataScopes(copyAndNormalizeScopes(dataScopes));
        agent.setOutputContract(StringUtils.trimToNull(outputContract));
    }

    private AgentDefinition copy(AgentDefinition source) {
        AgentDefinition copy = new AgentDefinition();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setAvatar(source.getAvatar());
        copy.setDescription(source.getDescription());
        copy.setStatus(source.getStatus());
        copy.setRuntimeType(source.getRuntimeType());
        copy.setRuntimeProfileId(source.getRuntimeProfileId());
        copy.setModelConfigId(source.getModelConfigId());
        copy.setSystemPrompt(source.getSystemPrompt());
        copy.setCapabilities(new LinkedHashSet<>(source.getCapabilities()));
        copy.setDataScopes(copyAndNormalizeScopes(source.getDataScopes()));
        copy.setOutputContract(source.getOutputContract());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setGmtCreate(source.getGmtCreate());
        copy.setGmtModified(source.getGmtModified());
        copy.setRevision(source.getRevision());
        return copy;
    }

    private void validateOutputContract(String outputContract) {
        if (StringUtils.isBlank(outputContract)) {
            return;
        }
        Map<?, ?> contract;
        try {
            contract = JSON.parseObject(outputContract, Map.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("agent output contract must be valid JSON", exception);
        }
        Object artifactValue = contract.get("requiredArtifacts");
        if (artifactValue != null && !(artifactValue instanceof List<?>)) {
            throw new IllegalArgumentException("requiredArtifacts must be an array");
        }
        for (Object value : artifactValue instanceof List<?> list ? list : List.of()) {
            if (!(value instanceof Map<?, ?> requirement) || requirement.get("type") == null) {
                throw new IllegalArgumentException("each required artifact must declare a type");
            }
            try {
                AgentArtifactTypeEnum.valueOf(String.valueOf(requirement.get("type")).toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unknown required artifact type", exception);
            }
            Object minimum = requirement.get("min");
            if (minimum != null && (!(minimum instanceof Number number) || number.intValue() <= 0)) {
                throw new IllegalArgumentException("required artifact min must be positive");
            }
        }
        Object sectionValue = contract.get("requiredSections");
        if (sectionValue != null && !(sectionValue instanceof List<?>)) {
            throw new IllegalArgumentException("requiredSections must be an array");
        }
        if (sectionValue instanceof List<?> sections
                && sections.stream().anyMatch(section -> !(section instanceof String text)
                || StringUtils.isBlank(text))) {
            throw new IllegalArgumentException("required report sections must be non-blank strings");
        }
    }

    private List<AgentDataScope> copyAndNormalizeScopes(List<AgentDataScope> scopes) {
        List<AgentDataScope> result = new ArrayList<>();
        for (AgentDataScope scope : scopes == null ? List.<AgentDataScope>of() : scopes) {
            AgentDataScope copy = AgentScopePolicy.copy(scope);
            copy.setMaxRows(copy.getMaxRows() == null ? DEFAULT_MAX_ROWS : copy.getMaxRows());
            copy.setTimeoutSeconds(copy.getTimeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS : copy.getTimeoutSeconds());
            result.add(copy);
        }
        return result;
    }

    private void validateScope(AgentDataScope scope) {
        if (scope == null || scope.getDataSourceId() == null) {
            throw new IllegalArgumentException("agent data scope datasource is required");
        }
        if (scope.getMaxRows() != null && scope.getMaxRows() <= 0) {
            throw new IllegalArgumentException("agent data scope maxRows must be positive");
        }
        if (scope.getTimeoutSeconds() != null && scope.getTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("agent data scope timeoutSeconds must be positive");
        }
        if (scope.getTableNames() != null && scope.getTableNames().stream().anyMatch(StringUtils::isBlank)) {
            throw new IllegalArgumentException("agent data scope table names must not be blank");
        }
        if (scope.getExcludedTableNames() != null
                && scope.getExcludedTableNames().stream().anyMatch(StringUtils::isBlank)) {
            throw new IllegalArgumentException("agent excluded table names must not be blank");
        }
    }
}

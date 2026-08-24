package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalModeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDataWikiBinding;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDefinition;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiResource;
import ai.chat2db.community.domain.api.service.datawiki.IDataWikiService;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

final class AgentDataWikiPolicy {

    private static final int DEFAULT_MAX_ROWS = 200;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private AgentDataWikiPolicy() {
    }

    static List<AgentDataWikiBinding> normalizeAndValidate(List<AgentDataWikiBinding> bindings,
                                                            List<String> legacyIds,
                                                            Long ownerId,
                                                            IDataWikiService service) {
        List<AgentDataWikiBinding> requested = new ArrayList<>();
        if (bindings != null) {
            requested.addAll(bindings);
        }
        LinkedHashSet<String> configuredIds = new LinkedHashSet<>();
        requested.stream().filter(Objects::nonNull).map(AgentDataWikiBinding::getDataWikiId)
                .filter(StringUtils::isNotBlank).map(String::trim).forEach(configuredIds::add);
        for (String legacyId : legacyIds == null ? List.<String>of() : legacyIds) {
            if (StringUtils.isNotBlank(legacyId) && configuredIds.add(legacyId.trim())) {
                AgentDataWikiBinding binding = new AgentDataWikiBinding();
                binding.setDataWikiId(legacyId.trim());
                requested.add(binding);
            }
        }

        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>();
        List<AgentDataWikiBinding> normalized = new ArrayList<>();
        for (AgentDataWikiBinding binding : requested) {
            if (binding == null || StringUtils.isBlank(binding.getDataWikiId())) {
                throw new IllegalArgumentException("agent DataWiki ids must not be blank");
            }
            String normalizedId = binding.getDataWikiId().trim();
            if (!normalizedIds.add(normalizedId)) {
                throw new IllegalArgumentException("DataWiki can only be bound once: " + normalizedId);
            }
            DataWikiDefinition wiki = service.get(normalizedId);
            if (ownerId != null && !Objects.equals(ownerId, wiki.getCreatedBy())) {
                throw new IllegalArgumentException("DataWiki does not belong to current user: " + normalizedId);
            }
            normalized.add(copyAndValidate(binding, normalizedId));
        }
        return normalized;
    }

    static List<AgentDataScope> effectiveScopes(List<AgentDataScope> explicitScopes,
                                                 List<AgentDataWikiBinding> bindings,
                                                 IDataWikiService service) {
        List<AgentDataScope> result = new ArrayList<>(AgentScopePolicy.copyScopes(explicitScopes));
        for (AgentDataWikiBinding binding : bindings == null ? List.<AgentDataWikiBinding>of() : bindings) {
            if (binding == null || StringUtils.isBlank(binding.getDataWikiId())) {
                continue;
            }
            DataWikiDefinition wiki;
            try {
                wiki = service.get(binding.getDataWikiId());
            } catch (NoSuchElementException ignored) {
                continue;
            }
            for (DataWikiResource resource : wiki.getResources()) {
                AgentDataScope contributed = scope(resource, binding);
                if (result.stream().noneMatch(existing -> AgentScopePolicy.contains(existing, contributed))) {
                    result.add(contributed);
                }
            }
        }
        return result;
    }

    static List<String> ids(List<AgentDataWikiBinding> bindings) {
        return (bindings == null ? List.<AgentDataWikiBinding>of() : bindings).stream()
                .filter(Objects::nonNull)
                .map(AgentDataWikiBinding::getDataWikiId)
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
    }

    static List<AgentDataWikiBinding> legacyBindings(List<String> ids) {
        return (ids == null ? List.<String>of() : ids).stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> {
                    AgentDataWikiBinding binding = new AgentDataWikiBinding();
                    binding.setDataWikiId(id.trim());
                    return binding;
                })
                .toList();
    }

    static List<AgentDataWikiBinding> copyBindings(List<AgentDataWikiBinding> bindings) {
        return (bindings == null ? List.<AgentDataWikiBinding>of() : bindings).stream()
                .filter(Objects::nonNull)
                .map(binding -> copy(binding, binding.getDataWikiId()))
                .toList();
    }

    private static AgentDataWikiBinding copyAndValidate(AgentDataWikiBinding binding, String normalizedId) {
        if (binding.getMaxRows() == null || binding.getMaxRows() <= 0
                || binding.getTimeoutSeconds() == null || binding.getTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("DataWiki binding limits must be positive");
        }
        if (binding.getApprovalMode() == null) {
            throw new IllegalArgumentException("DataWiki binding approval mode is required");
        }
        return copy(binding, normalizedId);
    }

    private static AgentDataWikiBinding copy(AgentDataWikiBinding source, String dataWikiId) {
        AgentDataWikiBinding copy = new AgentDataWikiBinding();
        copy.setDataWikiId(StringUtils.trimToNull(dataWikiId));
        copy.setMaxRows(source.getMaxRows());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        copy.setApprovalMode(source.getApprovalMode());
        copy.setAllowProduction(Boolean.TRUE.equals(source.getAllowProduction()));
        return copy;
    }

    private static AgentDataScope scope(DataWikiResource resource, AgentDataWikiBinding binding) {
        AgentDataScope scope = new AgentDataScope();
        scope.setDataSourceId(resource.getDataSourceId());
        scope.setDatabaseName(StringUtils.trimToNull(resource.getDatabaseName()));
        scope.setSchemaName(StringUtils.trimToNull(resource.getSchemaName()));
        scope.setTableNames(List.of(resource.getTableName()));
        scope.setExcludedTableNames(new ArrayList<>());
        scope.setMaxRows(binding.getMaxRows() == null ? DEFAULT_MAX_ROWS : binding.getMaxRows());
        scope.setTimeoutSeconds(binding.getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS : binding.getTimeoutSeconds());
        scope.setApprovalMode(binding.getApprovalMode() == null
                ? AgentApprovalModeEnum.RISK_BASED : binding.getApprovalMode());
        scope.setAllowProduction(Boolean.TRUE.equals(binding.getAllowProduction()));
        return scope;
    }
}

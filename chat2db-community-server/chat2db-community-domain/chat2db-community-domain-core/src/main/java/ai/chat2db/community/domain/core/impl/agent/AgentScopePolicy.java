package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalModeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AgentScopePolicy {

    private AgentScopePolicy() {
    }

    static List<AgentDataScope> requireAuthorizedScopes(List<AgentDataScope> requested, List<AgentDataScope> granted) {
        List<AgentDataScope> result = new ArrayList<>();
        for (AgentDataScope requestedScope : requested == null ? List.<AgentDataScope>of() : requested) {
            if (requestedScope == null || requestedScope.getDataSourceId() == null) {
                throw new IllegalArgumentException("task data scope datasource is required");
            }
            AgentDataScope matchingGrant = (granted == null ? List.<AgentDataScope>of() : granted).stream()
                    .filter(grant -> contains(grant, requestedScope))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "task data scope is outside the assigned agent access policy"));
            AgentDataScope snapshot = copy(requestedScope);
            snapshot.setMaxRows(minPositive(requestedScope.getMaxRows(), matchingGrant.getMaxRows()));
            snapshot.setTimeoutSeconds(minPositive(requestedScope.getTimeoutSeconds(), matchingGrant.getTimeoutSeconds()));
            snapshot.setAllowProduction(Boolean.TRUE.equals(requestedScope.getAllowProduction())
                    && Boolean.TRUE.equals(matchingGrant.getAllowProduction()));
            snapshot.setApprovalMode(stricterApprovalMode(
                    requestedScope.getApprovalMode(), matchingGrant.getApprovalMode()));
            result.add(snapshot);
        }
        return result;
    }

    static AgentDataScope copy(AgentDataScope source) {
        AgentDataScope copy = new AgentDataScope();
        copy.setDataSourceId(source.getDataSourceId());
        copy.setDatabaseName(StringUtils.trimToNull(source.getDatabaseName()));
        copy.setSchemaName(StringUtils.trimToNull(source.getSchemaName()));
        copy.setTableNames(normalizeNames(source.getTableNames()));
        copy.setExcludedTableNames(normalizeNames(source.getExcludedTableNames()));
        copy.setMaxRows(source.getMaxRows());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        copy.setApprovalMode(source.getApprovalMode());
        copy.setAllowProduction(Boolean.TRUE.equals(source.getAllowProduction()));
        return copy;
    }

    static List<AgentDataScope> copyScopes(List<AgentDataScope> scopes) {
        return (scopes == null ? List.<AgentDataScope>of() : scopes).stream()
                .map(AgentScopePolicy::copy)
                .toList();
    }

    private static boolean contains(AgentDataScope grant, AgentDataScope requested) {
        if (grant == null || !grant.getDataSourceId().equals(requested.getDataSourceId())) {
            return false;
        }
        if (!containsName(grant.getDatabaseName(), requested.getDatabaseName())
                || !containsName(grant.getSchemaName(), requested.getSchemaName())) {
            return false;
        }
        Set<String> excluded = normalizedSet(grant.getExcludedTableNames());
        Set<String> requestedTables = normalizedSet(requested.getTableNames());
        if (requestedTables.stream().anyMatch(excluded::contains)) {
            return false;
        }
        Set<String> grantedTables = normalizedSet(grant.getTableNames());
        return grantedTables.isEmpty() || (!requestedTables.isEmpty() && grantedTables.containsAll(requestedTables));
    }

    private static boolean containsName(String granted, String requested) {
        return StringUtils.isBlank(granted)
                || (StringUtils.isNotBlank(requested) && granted.equalsIgnoreCase(requested));
    }

    private static Integer minPositive(Integer requested, Integer granted) {
        if (requested == null) {
            return granted;
        }
        if (requested <= 0) {
            throw new IllegalArgumentException("task data scope limits must be positive");
        }
        return granted == null ? requested : Math.min(requested, granted);
    }

    private static AgentApprovalModeEnum stricterApprovalMode(AgentApprovalModeEnum requested,
                                                               AgentApprovalModeEnum granted) {
        AgentApprovalModeEnum requestedMode = requested == null ? AgentApprovalModeEnum.RISK_BASED : requested;
        AgentApprovalModeEnum grantedMode = granted == null ? AgentApprovalModeEnum.RISK_BASED : granted;
        return approvalRank(requestedMode) >= approvalRank(grantedMode) ? requestedMode : grantedMode;
    }

    private static int approvalRank(AgentApprovalModeEnum mode) {
        return switch (mode) {
            case NEVER -> 0;
            case RISK_BASED -> 1;
            case ALWAYS -> 2;
        };
    }

    private static List<String> normalizeNames(List<String> names) {
        if (names == null) {
            return new ArrayList<>();
        }
        return names.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static Set<String> normalizedSet(List<String> names) {
        Set<String> result = new HashSet<>();
        for (String name : names == null ? List.<String>of() : names) {
            if (StringUtils.isNotBlank(name)) {
                result.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }
}

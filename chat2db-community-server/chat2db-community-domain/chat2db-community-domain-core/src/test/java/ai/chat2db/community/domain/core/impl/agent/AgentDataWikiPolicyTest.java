package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalModeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDataWikiBinding;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDefinition;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiResource;
import ai.chat2db.community.domain.api.service.datawiki.IDataWikiService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentDataWikiPolicyTest {

    @Test
    void unionsWikiTablesWithoutDuplicatingExplicitlyAuthorizedTables() {
        AgentDataScope explicit = new AgentDataScope();
        explicit.setDataSourceId(1L);
        explicit.setDatabaseName("sales");
        explicit.setTableNames(List.of("orders"));

        AgentDataWikiBinding binding = new AgentDataWikiBinding();
        binding.setDataWikiId("wiki-1");
        binding.setMaxRows(75);
        binding.setTimeoutSeconds(15);
        binding.setApprovalMode(AgentApprovalModeEnum.ALWAYS);
        binding.setAllowProduction(true);

        List<AgentDataScope> scopes = AgentDataWikiPolicy.effectiveScopes(
                List.of(explicit), List.of(binding), service());

        assertEquals(2, scopes.size());
        assertEquals(List.of("orders"), scopes.get(0).getTableNames());
        assertEquals(List.of("customers"), scopes.get(1).getTableNames());
        assertEquals(75, scopes.get(1).getMaxRows());
        assertEquals(15, scopes.get(1).getTimeoutSeconds());
        assertEquals(AgentApprovalModeEnum.ALWAYS, scopes.get(1).getApprovalMode());
        assertEquals(true, scopes.get(1).getAllowProduction());
    }

    @Test
    void normalizesLegacyIdsAndRejectsInvalidPolicy() {
        List<AgentDataWikiBinding> legacy = AgentDataWikiPolicy.normalizeAndValidate(
                List.of(), List.of(" wiki-1 "), 7L, service());

        assertEquals(List.of("wiki-1"), AgentDataWikiPolicy.ids(legacy));
        assertEquals(200, legacy.get(0).getMaxRows());
        assertEquals(AgentApprovalModeEnum.RISK_BASED, legacy.get(0).getApprovalMode());

        AgentDataWikiBinding invalid = new AgentDataWikiBinding();
        invalid.setDataWikiId("wiki-1");
        invalid.setMaxRows(0);
        assertThrows(IllegalArgumentException.class, () -> AgentDataWikiPolicy.normalizeAndValidate(
                List.of(invalid), List.of(), 7L, service()));
    }

    private IDataWikiService service() {
        DataWikiResource orders = resource("orders");
        DataWikiResource customers = resource("customers");
        DataWikiDefinition wiki = new DataWikiDefinition();
        wiki.setId("wiki-1");
        wiki.setCreatedBy(7L);
        wiki.setResources(List.of(orders, customers));
        return (IDataWikiService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IDataWikiService.class}, (proxy, method, args) -> {
                    if ("get".equals(method.getName())) return wiki;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private DataWikiResource resource(String table) {
        DataWikiResource resource = new DataWikiResource();
        resource.setDataSourceId(1L);
        resource.setDatabaseName("sales");
        resource.setTableName(table);
        return resource;
    }
}

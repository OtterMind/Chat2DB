package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolScopePolicyTest {

    @Test
    void shouldRejectConnectionOutsideTaskScope() {
        AgentDataScope scope = scope();

        assertThrows(IllegalArgumentException.class,
                () -> AgentToolScopePolicy.requireConnection(scope, 2L, "analytics", "public"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentToolScopePolicy.requireConnection(scope, 1L, "other", "public"));
        assertDoesNotThrow(() -> AgentToolScopePolicy.requireConnection(scope, 1L, "ANALYTICS", "PUBLIC"));
    }

    @Test
    void shouldApplyTableAllowAndDenyLists() {
        AgentDataScope scope = scope();

        assertTrue(AgentToolScopePolicy.allowsTable(scope, "orders"));
        assertTrue(AgentToolScopePolicy.allowsTable(scope, "public.CUSTOMERS"));
        assertFalse(AgentToolScopePolicy.allowsTable(scope, "audit_log"));
        assertFalse(AgentToolScopePolicy.allowsTable(scope, "payments"));
    }

    @Test
    void shouldValidateEveryTableAndQualifierInSql() {
        AgentDataScope scope = scope();

        assertDoesNotThrow(() -> AgentToolScopePolicy.requireSql(scope,
                "select o.id from analytics.public.orders o join customers c on c.id = o.customer_id"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentToolScopePolicy.requireSql(scope, "select * from payments"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentToolScopePolicy.requireSql(scope, "select * from analytics.private.orders"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentToolScopePolicy.requireSql(scope, "select from"));
    }

    @Test
    void shouldCapRowsByTaskScope() {
        AgentDataScope scope = scope();
        scope.setMaxRows(75);

        assertEquals(75, AgentToolScopePolicy.capRows(scope, 300, 200, 500));
        assertEquals(50, AgentToolScopePolicy.capRows(scope, 50, 200, 500));
        assertEquals(200, AgentToolScopePolicy.capRows(null, null, 200, 500));
    }

    private AgentDataScope scope() {
        AgentDataScope scope = new AgentDataScope();
        scope.setDataSourceId(1L);
        scope.setDatabaseName("analytics");
        scope.setSchemaName("public");
        scope.setTableNames(List.of("orders", "customers", "audit_log"));
        scope.setExcludedTableNames(List.of("audit_log"));
        return scope;
    }
}

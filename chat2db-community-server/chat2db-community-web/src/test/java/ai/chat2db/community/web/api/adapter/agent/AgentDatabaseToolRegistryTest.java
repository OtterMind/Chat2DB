package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseRequest.Query;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseResult;
import ai.chat2db.community.domain.api.service.agent.AgentDatabaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AgentDatabaseToolRegistryTest {
    @Test
    void exposesIndependentSchemasAndRejectsLegacyOrCoercedArguments() {
        AtomicReference<Object> input = new AtomicReference<>();
        var registry = registry(input, AgentDatabaseResult.success(null, List.of(), null, null, List.of()));
        assertEquals(Set.of("db_list_datasources", "db_list_databases", "db_list_schemas", "db_list_tables", "db_list_columns", "db_describe_tables", "db_query"), registry.names());
        var query = registry.definitions().stream().filter(t -> t.name().equals("db_query")).findFirst().orElseThrow();
        assertEquals(List.of("dataSourceId", "sql"), query.parameters().get("required"));
        assertEquals(false, query.parameters().get("additionalProperties"));
        assertFalse(query.promptGuidelines().isEmpty());
        assertFalse(query.promptSnippet().isBlank());
        var fields = (Map<?, ?>) query.parameters().get("properties");
        assertTrue(((Map<?, ?>) fields.get("database")).containsKey("anyOf"));
        assertFalse(((Map<?, ?>) fields.get("dataSourceId")).containsKey("anyOf"));
        assertFalse(registry.execute("execute_sql", Map.of("sql", "SELECT 1")).ok());
        var legacy = registry.execute("db_query", Map.of("dataSourceId", "7", "sql", "SELECT 1", "databaseName", "app"));
        assertEquals("databaseName", legacy.error().field());
        assertFalse(registry.execute("db_query", Map.of("dataSourceId", 7, "sql", "SELECT 1")).ok());
        assertFalse(registry.execute("db_query", Map.of("dataSourceId", "7", "sql", "SELECT 1", "pageSize", "100")).ok());
        assertNull(input.get());
        assertTrue(registry.execute("db_query", Map.of("dataSourceId", "7", "database", "app", "sql", "SELECT 1", "pageSize", 100)).ok());
        assertEquals(100, ((Query)input.get()).pageSize());
        Map<String, Object> nullable = new HashMap<>(); nullable.put("dataSourceId", "7"); nullable.put("sql", "SELECT 1");
        nullable.put("database", null); nullable.put("schema", null);
        assertTrue(registry.execute("db_query", nullable).ok());
    }

    @Test
    void oversizedResultsRemainValidStructuredErrors() throws Exception {
        var registry = registry(new AtomicReference<>(), AgentDatabaseResult.success(null, "x".repeat(600000), null, null, List.of()));
        var result = registry.execute("db_query", Map.of("dataSourceId", "7", "sql", "SELECT body FROM samples", "pageSize", 100));
        assertFalse(result.ok());
        assertEquals("RESULT_TOO_LARGE", result.error().code());
        assertEquals(50, result.nextAction().arguments().get("pageSize"));
        assertEquals(1, result.nextAction().arguments().get("page"));
        String json = new ObjectMapper().writeValueAsString(result);
        assertFalse(new ObjectMapper().readTree(json).get("ok").asBoolean());
        assertFalse(json.contains("Output truncated"));
    }
    private AgentDatabaseToolRegistry registry(AtomicReference<Object> input, AgentDatabaseResult<?> result) {
        var service = (AgentDatabaseService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AgentDatabaseService.class},
                (p,m,a) -> { input.set(a[0]); return result; });
        return new AgentDatabaseToolRegistry(service);
    }
}

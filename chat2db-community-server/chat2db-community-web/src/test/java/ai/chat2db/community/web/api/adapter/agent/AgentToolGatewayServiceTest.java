package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.model.agent.*;
import ai.chat2db.community.domain.api.service.agent.*;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseResult;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolGatewayServiceTest {
    @Test
    void runsIndependentDatabaseToolsWithSessionIdentityAndDeduplicatesExecution() throws Exception {
        Context owner = new Context();
        Context caller = new Context();
        AtomicInteger executions = new AtomicInteger();
        AgentDatabaseService domainTools = (AgentDatabaseService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AgentDatabaseService.class}, (proxy, method, args) -> {
                    assertSame(owner, ContextUtils.queryThreadContext());
                    executions.incrementAndGet();
                    return AgentDatabaseResult.success(null, List.of("database-list"), null, null, List.of());
                });
        LocalDateTime now = LocalDateTime.now();
        AgentSession session = new AgentSession(2, "session", 1L,
                new AgentDefinition("DEFAULT", "Agent", null, "existing prompt", AgentRuntimeType.PI, "model", 1),
                new AgentRuntimeBinding(AgentRuntimeType.PI, "1", "1", "session", null, 1),
                AgentSessionStatus.RUNNING, "test", 1, now, now);
        AgentRun run = new AgentRun("run", "session", AgentRunStatus.RUNNING,
                new AgentModelSnapshot("model", 1, "OPENAI", "model", null, null),
                "message", "request", "run", 1, 1, null, null);
        AgentSessionStorage sessions = (AgentSessionStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AgentSessionStorage.class}, (proxy, method, args) -> session);
        AgentRunStorage runs = (AgentRunStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AgentRunStorage.class}, (proxy, method, args) ->
                        method.getName().equals("list") ? List.of(run) : run);
        AgentToolGatewayService gateway = new AgentToolGatewayService(
                new AgentDatabaseToolRegistry(domainTools), sessions, runs, () -> 1L,
                null, List.of(), 11837);
        try {
            ContextUtils.setContext(owner);
            var access = gateway.issue("session", event -> {});
            var catalog = gateway.listTools();
            assertEquals(7, catalog.stream().filter(tool -> tool.category() == AgentToolState.Category.BUILTIN).count());
            assertTrue(catalog.stream().anyMatch(tool -> tool.name().equals("db_search_datasources")
                    && tool.status() == AgentToolState.Status.ENABLED));
            assertTrue(catalog.stream().filter(tool -> tool.category() == AgentToolState.Category.BUILTIN)
                    .allMatch(tool -> tool.status() == AgentToolState.Status.UNAVAILABLE));
            ContextUtils.setContext(caller);
            assertTrue(gateway.activeTools(access.ticket(), "127.0.0.1").contains("db_search_datasources"));
            assertFalse(gateway.activeTools(access.ticket(), "127.0.0.1").contains("bash"));
            assertThrows(SecurityException.class, () -> gateway.activeTools(access.ticket(), "192.0.2.1"));
            assertEquals(List.of("database-list"), gateway.execute(
                    access.ticket(), "127.0.0.1", "call", "db_search_datasources", Map.of()).data());
            assertEquals(List.of("database-list"), gateway.execute(
                    access.ticket(), "127.0.0.1", "call", "db_search_datasources", Map.of()).data());
            assertEquals(1, executions.get());
            assertSame(caller, ContextUtils.queryThreadContext());
            assertThrows(IllegalArgumentException.class, () -> gateway.execute(
                    access.ticket(), "127.0.0.1", "call", "db_search_datasources", Map.of("changed", true)));
            gateway.revoke(access.ticket());
            assertThrows(SecurityException.class, () -> gateway.activeTools(access.ticket(), "127.0.0.1"));
        } finally {
            ContextUtils.removeContext();
        }
    }
}

package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.model.agent.*;
import ai.chat2db.community.domain.api.service.agent.*;

import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AgentNativeToolApprovalTest {
    @Test
    void nativeFilesAreAvailableAndShellApprovalFreezesDirectory() throws Exception {
        AtomicReference<String> directory = new AtomicReference<>("/first");
        AtomicInteger decisions = new AtomicInteger();
        Set<String> enabledTools = new HashSet<>();
        var disableWhileWaiting = new java.util.concurrent.atomic.AtomicBoolean();
        AgentWorkspaceService workspace = new AgentWorkspaceService() {
            public AgentWorkspaceSettings get() { return new AgentWorkspaceSettings(directory.get()); }
            public AgentWorkspaceSettings update(String value) { directory.set(value); return get(); }
            public String resolveWorkingDirectory(String sessionId) { return directory.get(); }
            public String selectDirectory() { throw new UnsupportedOperationException(); }
            public boolean isToolEnabled(String name) { return enabledTools.contains(name); }
            public void setToolEnabled(String name, boolean enabled) { if (enabled) enabledTools.add(name); else enabledTools.remove(name); }
        };
        var now = LocalDateTime.now();
        AgentSession session = new AgentSession(2, "session", 1L,
                new AgentDefinition("DEFAULT", "Agent", null, "existing prompt", AgentRuntimeType.PI, "model", 1),
                new AgentRuntimeBinding(AgentRuntimeType.PI, "1", "1", "session", null, 1),
                AgentSessionStatus.RUNNING, "test", 1, now, now);
        AgentRun run = new AgentRun("run", "session", AgentRunStatus.RUNNING, new AgentModelSnapshot("model", 1, "OPENAI", "model", null, null), "message", "request", "run", 1, 1, null, null);
        AgentSessionStorage sessions = proxy(AgentSessionStorage.class, (method, args) -> session);
        AgentRunStorage runs = proxy(AgentRunStorage.class, (method, args) -> method.equals("list") ? List.of(run) : run);
        AgentApprovalService approvals = proxy(AgentApprovalService.class, (method, args) -> {
            decisions.incrementAndGet();
            ((Runnable) args[2]).run();
            directory.set("/second");
            if (disableWhileWaiting.get()) enabledTools.remove(AgentNativeTools.currentPlatform().get(0));
            return ((java.util.function.BooleanSupplier) args[3]).getAsBoolean();
        });
        AgentDatabaseService database = proxy(AgentDatabaseService.class, (method, args) -> null);
        var gateway = new AgentToolGatewayService(new AgentDatabaseToolRegistry(database),
                sessions, runs, () -> 1L, approvals, List.of(workspace), 11847);
        var events = new ArrayList<ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent>();
        try {
            ContextUtils.setContext(new Context());
            var access = gateway.issue("session", events::add);
            String shell = AgentNativeTools.currentPlatform().get(0);
            assertFalse(gateway.activeTools(access.ticket(), "127.0.0.1").contains("read"));
            assertThrows(IllegalArgumentException.class, () -> gateway.prepareNative(access.ticket(), "127.0.0.1", "disabled", "read", Map.of("path", "a.csv")));
            enabledTools.addAll(AgentNativeTools.currentPlatform());
            assertTrue(gateway.activeTools(access.ticket(), "127.0.0.1").containsAll(AgentNativeTools.currentPlatform()));
            assertEquals(7, gateway.listTools().stream().filter(t -> t.category() == AgentToolState.Category.BUILTIN
                    && t.status() == AgentToolState.Status.ENABLED).count());
            assertEquals("/first", gateway.prepareNative(access.ticket(), "127.0.0.1", "read", "read", Map.of("path", "a.csv")).workingDirectory());
            assertEquals(0, decisions.get());
            var prepared = gateway.prepareNative(access.ticket(), "127.0.0.1", "shell", shell, Map.of("command", "pwd"));
            assertEquals("/first", prepared.workingDirectory());
            assertEquals("/first", events.get(0).payload().get("workingDirectory"));
            assertEquals(prepared, gateway.prepareNative(access.ticket(), "127.0.0.1", "shell", shell, Map.of("command", "pwd")));
            assertEquals(1, decisions.get());
            assertEquals("/second", gateway.prepareNative(access.ticket(), "127.0.0.1", "next", "ls", Map.of()).workingDirectory());
            assertThrows(IllegalArgumentException.class, () -> gateway.prepareNative(access.ticket(), "127.0.0.1", "shell", shell, Map.of("command", "changed")));
            assertThrows(SecurityException.class, () -> gateway.prepareNative(access.ticket(), "192.0.2.1", "outside", "read", Map.of()));
            enabledTools.remove("read");
            assertFalse(gateway.activeTools(access.ticket(), "127.0.0.1").contains("read"));
            assertThrows(IllegalArgumentException.class, () -> gateway.prepareNative(access.ticket(), "127.0.0.1", "read", "read", Map.of("path", "a.csv")));
            disableWhileWaiting.set(true);
            assertThrows(IllegalStateException.class, () -> gateway.prepareNative(access.ticket(), "127.0.0.1", "disabled-pending", shell, Map.of("command", "pwd")));
            String otherShell = shell.equals("bash") ? "powershell" : "bash";
            assertThrows(IllegalArgumentException.class, () -> gateway.prepareNative(access.ticket(), "127.0.0.1", "other", otherShell, Map.of("command", "pwd")));
        } finally { ContextUtils.removeContext(); }
    }

    @Test
    void windowsUsesPowerShellAndUnixUsesBash() {
        assertTrue(AgentNativeTools.forPlatform("Windows 11").contains("powershell"));
        assertFalse(AgentNativeTools.forPlatform("Windows 11").contains("bash"));
        for (String os : List.of("Mac OS X", "Linux", "Darwin")) {
            assertTrue(AgentNativeTools.forPlatform(os).contains("bash"));
            assertFalse(AgentNativeTools.forPlatform(os).contains("powershell"));
        }
    }

    private interface Invocation { Object call(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Invocation call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.call(method.getName(), args)));
    }
}

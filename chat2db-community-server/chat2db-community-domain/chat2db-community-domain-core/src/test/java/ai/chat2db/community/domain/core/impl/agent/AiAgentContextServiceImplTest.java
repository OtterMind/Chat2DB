package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.request.agent.AiAgentRunContextRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiAgentContextServiceImplTest {
    @Test
    void resolvesTrustedSourceMetadataAndCapturesTimeInTheUsersZone() {
        AtomicInteger reads = new AtomicInteger();
        IWorkspaceStorageFacade storage = (IWorkspaceStorageFacade) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IWorkspaceStorageFacade.class}, (proxy, method, args) -> {
                    assertEquals("queryDataSourceById", method.getName());
                    assertEquals(false, args[1]);
                    reads.incrementAndGet();
                    if (!Long.valueOf(123).equals(args[0])) return null;
                    var source = new WorkspaceDataSource();
                    source.setId(123L); source.setAlias("My database"); source.setType("MYSQL");
                    return source;
                });
        var service = new AiAgentContextServiceImpl(storage, Clock.fixed(Instant.parse("2026-09-10T16:15:00Z"), ZoneOffset.UTC));
        var object = new AiAgentRunContextRequest.ObjectReference("123", "sales", "a", "TABLE", "orders", "MENTION");
        var context = service.resolve(new AiAgentRunContextRequest("Asia/Shanghai",
                new AiAgentRunContextRequest.Scope("123", "sales", "a"), List.of(object, object,
                new AiAgentRunContextRequest.ObjectReference("123", "sales", "b", "TABLE", "orders", "MENTION"))));
        assertEquals("2026-09-11T00:15:00+08:00", context.environment().requestTime());
        assertEquals("My database", context.selection().dataSourceName());
        assertEquals(2, context.objects().size());
        assertEquals(1, reads.get());
        assertThrows(IllegalArgumentException.class, () -> service.resolve(new AiAgentRunContextRequest("Bad/Zone", null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.resolve(new AiAgentRunContextRequest("UTC",
                new AiAgentRunContextRequest.Scope("456", null, null), null)));
    }

    @Test
    void handlesDstAndKeepsLegacyRequestsCompatibleWithAnExplicitUtcFallback() {
        var service = new AiAgentContextServiceImpl(null, Clock.fixed(Instant.parse("2026-03-08T07:30:00Z"), ZoneOffset.UTC));
        var dst = service.resolve(new AiAgentRunContextRequest("America/New_York", null, null));
        assertEquals("2026-03-08T03:30:00-04:00", dst.environment().requestTime());
        var legacy = service.resolve(null);
        assertEquals("UTC", legacy.environment().timeZone());
        assertEquals("UTC_FALLBACK", legacy.environment().timeZoneSource());
        assertNull(legacy.selection());
        assertTrue(legacy.objects().isEmpty());
    }
}

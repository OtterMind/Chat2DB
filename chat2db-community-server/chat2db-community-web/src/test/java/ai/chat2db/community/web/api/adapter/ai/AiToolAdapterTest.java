package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.domain.api.service.ai.IAiToolService;
import ai.chat2db.community.web.api.converter.ai.AiToolContextConverter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiToolAdapterTest {

    @Test
    void marksReturnedSqlFailureAsFailed() {
        AtomicReference<Map<String, Object>> trace = new AtomicReference<>();
        AiToolAdapter adapter = adapterReturning("SQL execution failed: syntax error");

        adapter.executeSql("select group from channels", 10, 7L, "oneapi", null, toolContext(trace));

        assertEquals("FAILED", trace.get().get("status"));
        assertFalse((Boolean) trace.get().get("success"));
    }

    @Test
    void emitsFailedResultBeforePropagatingToolException() {
        AtomicReference<Map<String, Object>> trace = new AtomicReference<>();
        IAiToolService service = proxy((proxy, method, args) -> {
            throw new IllegalStateException("database unavailable");
        });
        AiToolAdapter adapter = new AiToolAdapter(service, new AiToolContextConverter());

        assertThrows(IllegalStateException.class,
                () -> adapter.listAllDataSources(toolContext(trace)));
        assertEquals("FAILED", trace.get().get("status"));
        assertEquals("database unavailable", trace.get().get("content"));
    }

    private AiToolAdapter adapterReturning(String result) {
        return new AiToolAdapter(proxy((proxy, method, args) -> result), new AiToolContextConverter());
    }

    private IAiToolService proxy(java.lang.reflect.InvocationHandler handler) {
        return (IAiToolService) Proxy.newProxyInstance(
                IAiToolService.class.getClassLoader(), new Class<?>[]{IAiToolService.class}, handler);
    }

    private ToolContext toolContext(AtomicReference<Map<String, Object>> trace) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(AiChatTraceSupport.TRACE_EMITTER_KEY,
                (Consumer<Map<String, Object>>) payload -> trace.set(new LinkedHashMap<>(payload)));
        return new ToolContext(context);
    }
}

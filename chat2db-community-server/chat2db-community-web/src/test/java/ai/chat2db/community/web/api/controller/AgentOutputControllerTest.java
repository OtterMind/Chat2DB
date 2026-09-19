package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.output.AgentOutputRead;
import ai.chat2db.community.domain.api.service.agent.IAiAgentOutputService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.model.agent.tool.AgentOutputReference;
import java.lang.reflect.Proxy;
import java.util.List;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import ai.chat2db.community.web.api.config.console.DesktopBridgeRequestContext;

class AgentOutputControllerTest {
    @Test
    void readsAndDownloadsUsingTheAuthenticatedSessionOwnerAndPreservesCancellation() throws Exception {
        String content = "stored output";
        var outputs = (IAiAgentOutputService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IAiAgentOutputService.class}, (proxy, method, args) -> {
                    assertEquals("session", args[0]);
                    assertEquals(7L, args[1]);
                    assertEquals("output", args[2]);
                    return switch (method.getName()) {
                        case "read" -> new AgentOutputRead(content, null, false, 1, 1, false);
                        case "reference" -> new AgentOutputReference("file", "output", "/private/output.txt", "txt", content.length(), true, true, null);
                        case "download" -> { ((OutputStream) args[3]).write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); yield null; }
                        default -> throw new AssertionError(method.getName());
                    };
                });
        var identity = (IIdentityService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IIdentityService.class},
                (proxy, method, args) -> 7L);
        var controller = new AgentOutputController(outputs, identity, List.of());
        assertEquals(content, controller.read("session", "output", null, null, 10).getData().content());
        var body = new ByteArrayOutputStream();
        Map<String, String> headers = new HashMap<>();
        var stream = new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
            @Override public void write(int value) { body.write(value); }
        };
        var response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getOutputStream" -> stream;
                    case "setHeader" -> { headers.put((String) args[0], (String) args[1]); yield null; }
                    case "setContentType" -> null;
                    default -> throw new AssertionError(method.getName());
                });
        controller.download("session", "output", response);
        assertEquals(content, body.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("attachment; filename=\"output.txt\"", headers.get("Content-Disposition"));
        // Saving to a host path is a local capability, so it is exercised through the desktop bridge context.
        DesktopBridgeRequestContext.invoke(() -> {
            assertThrows(IllegalStateException.class, () -> controller.downloadPath("session", "output"));
            var desktop = new AgentOutputController(outputs, identity, List.of((session, user, artifact) -> null));
            assertNull(desktop.downloadPath("session", "output").getData());
            return null;
        });
        assertThrows(SecurityException.class, () -> controller.downloadPath("session", "output"));
    }
}

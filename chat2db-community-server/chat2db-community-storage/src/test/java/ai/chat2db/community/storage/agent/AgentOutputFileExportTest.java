package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.service.agent.IAiAgentOutputService;
import ai.chat2db.community.tools.model.agent.tool.AgentOutputReference;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AgentOutputFileExportTest {
    @TempDir Path directory;
    @Test
    void cancelledSaveNeverWritesAndSuccessfulSaveStreamsTheOriginalContent() throws Exception {
        Path root = Files.createDirectory(directory.resolve("sessions"));
        AtomicInteger downloads = new AtomicInteger();
        String content = "结果".repeat(10000);
        IAiAgentOutputService outputs = (IAiAgentOutputService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IAiAgentOutputService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "reference" -> new AgentOutputReference("file", "result", root.resolve("result.txt").toString(), "txt", 60000, true, true, null);
                    case "managedRoot" -> root;
                    case "download" -> { downloads.incrementAndGet(); ((OutputStream) args[3]).write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); yield null; }
                    default -> throw new AssertionError(method.getName());
                });
        assertNull(new AgentOutputFileExport(outputs, name -> null).save("session", 1L, "result"));
        assertEquals(0, downloads.get());
        Path destination = directory.resolve("selected.txt");
        assertEquals(destination.getParent().toRealPath().resolve(destination.getFileName()).toString(), new AgentOutputFileExport(outputs, name -> destination.toString()).save("session", 1L, "result"));
        assertEquals(content, Files.readString(destination));
        assertEquals(1, downloads.get());
        assertThrows(SecurityException.class, () -> new AgentOutputFileExport(outputs, name -> root.resolve("overwrite.txt").toString()).save("session", 1L, "result"));
        assertEquals(1, downloads.get());
    }
}

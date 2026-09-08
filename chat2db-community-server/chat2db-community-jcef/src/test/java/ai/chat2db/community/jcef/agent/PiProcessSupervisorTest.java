package ai.chat2db.community.jcef.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiProcessSupervisorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsWithLockedArgumentsAndAnIsolatedEnvironment() throws Exception {
        PiRuntimeLayout layout = runtimeLayout();
        Path sessionDataRoot = temporaryDirectory.resolve("storage/ai-chat-history-v2/runtime/pi");
        Path extension = Files.writeString(temporaryDirectory.resolve("extension.js"), "extension");
        FakeProcess process = new FakeProcess();
        ProcessBuilder[] captured = new ProcessBuilder[1];
        PiProcessSupervisor supervisor = new PiProcessSupervisor(layout, sessionDataRoot, 1, builder -> {
            captured[0] = builder;
            return process;
        });

        supervisor.start("session-one", "external-one", List.of(extension));

        assertEquals(1, supervisor.size());
        assertEquals(1, captured[0].environment().size());
        assertTrue(captured[0].environment().containsKey("PI_CODING_AGENT_DIR"));
        assertTrue(captured[0].command().containsAll(List.of(
                "--mode", "rpc", "--no-builtin-tools", "--no-extensions",
                "--no-skills", "--no-prompt-templates", "--no-themes",
                "--no-context-files", "--no-approve", "--offline")));
        assertThrows(IllegalStateException.class,
                () -> supervisor.start("session-two", "external-two", List.of(extension)));

        supervisor.close();
        assertFalse(process.isAlive());
        assertEquals(0, supervisor.size());
    }

    @Test
    void rejectsSessionPathTraversal() throws Exception {
        PiProcessSupervisor supervisor = new PiProcessSupervisor(
                runtimeLayout(), temporaryDirectory.resolve("storage/ai-chat-history-v2/runtime/pi"), 1,
                builder -> new FakeProcess());

        assertThrows(Exception.class,
                () -> supervisor.start("../outside", "external", List.of()));
    }

    private PiRuntimeLayout runtimeLayout() throws Exception {
        PiRuntimeLayout layout = new PiRuntimeLayout(temporaryDirectory.resolve("runtime"), "0.85.1");
        Path executable = layout.executable(
                System.getProperty("os.name", "unknown"), System.getProperty("os.arch", "unknown"));
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "runtime");
        executable.toFile().setExecutable(true, true);
        return layout;
    }

    private static final class FakeProcess extends Process {
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private boolean alive = true;
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { alive = false; exit.complete(this); }
        @Override public boolean isAlive() { return alive; }
        @Override public CompletableFuture<Process> onExit() { return exit; }
    }
}

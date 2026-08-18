package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderProcessRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void restartReapsOnlyProcessWithMatchingPidStartTimeAndExecutable() throws Exception {
        Path root = tempDir.resolve("runtime-root").toAbsolutePath();
        Path workspace = root.resolve("run-1-attempt-1");
        Files.createDirectories(workspace);
        Process process = sleeper();
        try {
            Instant start = process.info().startInstant().orElseThrow();
            String executable = javaExecutable().toString();
            ProviderProcessRegistry first = new ProviderProcessRegistry(root);
            first.register("daemon-1", AgentRuntimeProviderEnum.CODEX, "run-1", 1,
                    "execution-1", process.pid(), start, executable, workspace);

            ProviderProcessRegistry restarted = new ProviderProcessRegistry(root);
            ProviderProcessRegistry.RecoveryReport report = restarted.reapOrphans(
                    "daemon-1", AgentRuntimeProviderEnum.CODEX);

            assertEquals(1, report.recovered().size());
            assertEquals(0, report.quarantined().size());
            process.waitFor(5, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            assertTrue(Files.readString(root.resolve(ProviderProcessRegistry.FILE_NAME)).contains("[ ]")
                    || Files.readString(root.resolve(ProviderProcessRegistry.FILE_NAME)).trim().equals("[]"));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void pidReuseGuardQuarantinesIdentityMismatchInsteadOfKillingProcess() throws Exception {
        Path root = tempDir.resolve("quarantine-root").toAbsolutePath();
        Path workspace = root.resolve("run-2-attempt-1");
        Files.createDirectories(workspace);
        Process process = sleeper();
        try {
            ProviderProcessRegistry registry = new ProviderProcessRegistry(root);
            registry.register("daemon-1", AgentRuntimeProviderEnum.HERMES, "run-2", 1,
                    "execution-2", process.pid(), process.info().startInstant().orElseThrow(),
                    javaExecutable().resolveSibling("not-the-java-process").toString(), workspace);

            ProviderProcessRegistry.RecoveryReport report = registry.reapOrphans(
                    "daemon-1", AgentRuntimeProviderEnum.HERMES);

            assertEquals(0, report.recovered().size());
            assertEquals(1, report.quarantined().size());
            assertTrue(process.isAlive());
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private Process sleeper() throws Exception {
        return new ProcessBuilder(javaExecutable().toString(), "-cp", System.getProperty("java.class.path"),
                Sleeper.class.getName()).start();
    }

    private Path javaExecutable() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toAbsolutePath();
    }

    public static final class Sleeper {
        public static void main(String[] args) throws Exception {
            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
        }
    }
}

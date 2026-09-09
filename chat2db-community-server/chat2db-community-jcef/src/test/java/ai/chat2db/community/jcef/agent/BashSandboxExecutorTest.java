package ai.chat2db.community.jcef.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BashSandboxExecutorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void executesInsideWorkspaceAndCannotReadOrWriteOutsideIt() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("mac"));
        Path protectedFile = temporaryDirectory.resolve("outside.txt");
        Files.writeString(protectedFile, "outside-private-value");
        BashSandboxExecutor shell = shell();
        String result = shell.execute(shell.prepare("session", "printf inside-value > result.txt; cat result.txt; cat '"
                + protectedFile + "'; printf changed > '" + protectedFile + "'"), () -> false);
        assertTrue(result.contains("inside-value"), result);
        assertFalse(result.contains("outside-private-value"), result);
        assertEquals("outside-private-value", Files.readString(protectedFile));
        assertEquals("inside-value", Files.readString(temporaryDirectory.resolve("workspaces/session/result.txt")));
    }

    @Test
    void cancelsTheRunningCommand() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("mac"));
        AtomicBoolean cancelled = new AtomicBoolean();
        BashSandboxExecutor shell = shell();
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
            try {
                return shell.execute(shell.prepare("session", "sleep 30"), cancelled::get);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        Thread.sleep(500);
        cancelled.set(true);
        assertTrue(result.get(5, TimeUnit.SECONDS).contains("cancelled"));
    }

    @Test
    void directoryChangesCannotRedirectAnAlreadyPreparedCommand() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("mac"));
        BashSettingsService settings = new BashSettingsService(
                new BashSettingsServiceTest.MemorySettings(), temporaryDirectory.resolve("workspaces"));
        Path first = Files.createDirectory(temporaryDirectory.resolve("first space"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("second"));
        settings.update(first.toString());
        BashSandboxExecutor shell = new BashSandboxExecutor(settings);
        var approved = shell.prepare("session", "printf frozen > result.txt");
        settings.update(second.toString());
        shell.execute(approved, () -> false);
        assertEquals("frozen", Files.readString(first.resolve("result.txt")));
        assertFalse(Files.exists(second.resolve("result.txt")));
        assertEquals(second.toRealPath().toString(), shell.prepare("session", "pwd").workingDirectory());
    }

    @Test
    void rejectsDirectoryReplacedWithSymlinkAfterApproval() throws Exception {
        Path selected = Files.createDirectory(temporaryDirectory.resolve("selected"));
        Path other = Files.createDirectory(temporaryDirectory.resolve("other"));
        BashSettingsService settings = new BashSettingsService(
                new BashSettingsServiceTest.MemorySettings(), temporaryDirectory.resolve("workspaces"));
        settings.update(selected.toString());
        BashSandboxExecutor shell = new BashSandboxExecutor(settings);
        var approved = shell.prepare("session", "printf unexpected > result.txt");
        Files.delete(selected);
        Files.createSymbolicLink(selected, other);
        assertThrows(ai.chat2db.community.tools.exception.BusinessException.class,
                () -> shell.execute(approved, () -> false));
        assertFalse(Files.exists(other.resolve("result.txt")));
    }

    private BashSandboxExecutor shell() {
        return new BashSandboxExecutor(new BashSettingsService(
                new BashSettingsServiceTest.MemorySettings(), temporaryDirectory.resolve("workspaces")));
    }
}

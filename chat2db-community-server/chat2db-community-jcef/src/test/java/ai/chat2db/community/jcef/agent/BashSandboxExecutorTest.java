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
        BashSandboxExecutor shell = new BashSandboxExecutor(temporaryDirectory.resolve("workspaces"));
        String result = shell.execute("session", "printf inside-value > result.txt; cat result.txt; cat '"
                + protectedFile + "'; printf changed > '" + protectedFile + "'", () -> false);
        assertTrue(result.contains("inside-value"), result);
        assertFalse(result.contains("outside-private-value"), result);
        assertEquals("outside-private-value", Files.readString(protectedFile));
        assertEquals("inside-value", Files.readString(temporaryDirectory.resolve("workspaces/session/result.txt")));
    }

    @Test
    void cancelsTheRunningCommand() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("mac"));
        AtomicBoolean cancelled = new AtomicBoolean();
        BashSandboxExecutor shell = new BashSandboxExecutor(temporaryDirectory.resolve("workspaces"));
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
            try {
                return shell.execute("session", "sleep 30", cancelled::get);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        Thread.sleep(500);
        cancelled.set(true);
        assertTrue(result.get(5, TimeUnit.SECONDS).contains("cancelled"));
    }
}

package ai.chat2db.community.jcef.terminal;

import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalSessionManagerTest {
    @TempDir
    Path directory;

    @AfterEach
    void resetAfterEach() {
        TerminalSessionManager.resetEventPublisherForTests();
    }

    @Test
    void createsResizableInteractiveSessionInRequestedDirectory() throws Exception {
        Map<String, Object> session = TerminalSessionManager.create(directory, 80, 24);
        String sessionId = (String) session.get("sessionId");
        try {
            assertNotNull(sessionId);
            assertFalse(((String) session.get("shell")).isBlank());
            assertEquals("system", session.get("shellId"));
            assertTrue((Boolean) TerminalSessionManager.status(sessionId).get("alive"));
            TerminalSessionManager.resize(sessionId, 100, 30);
            TerminalSessionManager.write(sessionId, "echo chat2db-terminal-test\n");
        } finally {
            TerminalSessionManager.kill(sessionId);
        }
        assertFalse((Boolean) TerminalSessionManager.status(sessionId).get("alive"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void reportsRunningChildProcessAsBusy() throws Exception {
        Map<String, Object> session = TerminalSessionManager.create(directory, 80, 24);
        String sessionId = (String) session.get("sessionId");
        try {
            TerminalSessionManager.write(sessionId, "sleep 10\n");
            boolean busy = false;
            for (int attempt = 0; attempt < 40 && !busy; attempt++) {
                Thread.sleep(50);
                busy = (Boolean) TerminalSessionManager.status(sessionId).get("busy");
            }
            assertTrue(busy);
        } finally {
            TerminalSessionManager.kill(sessionId);
        }
    }

    @Test
    void duplicatesIntoIndependentSessionWithSameWorkingDirectory() throws Exception {
        Map<String, Object> original = TerminalSessionManager.create(directory, 80, 24);
        String originalSessionId = (String) original.get("sessionId");
        Map<String, Object> duplicate = TerminalSessionManager.duplicate(originalSessionId, 100, 30);
        String duplicateSessionId = (String) duplicate.get("sessionId");
        try {
            assertNotEquals(originalSessionId, duplicateSessionId);
            assertEquals(original.get("cwd"), duplicate.get("cwd"));
            assertEquals(original.get("shellId"), duplicate.get("shellId"));
            TerminalSessionManager.kill(originalSessionId);
            assertFalse((Boolean) TerminalSessionManager.status(originalSessionId).get("alive"));
            assertTrue((Boolean) TerminalSessionManager.status(duplicateSessionId).get("alive"));
        } finally {
            TerminalSessionManager.kill(originalSessionId);
            TerminalSessionManager.kill(duplicateSessionId);
        }
    }

    @Test
    void resolvesConfiguredUserHomeForDefaultTerminalDirectory() {
        assertEquals(directory, TerminalSessionManager.resolveUserHomeDirectory(directory.toString()));
    }

    @Test
    void resolvesDefaultShellCandidatesForCurrentOs() {
        assertFalse(TerminalSessionManager.resolveShellCandidates("system").isEmpty());
        assertFalse(TerminalSessionManager.resolveShellCandidates(null).isEmpty());
        assertFalse(TerminalSessionManager.resolveShellCandidates("").isEmpty());
    }

    @Test
    void rejectsUnknownShellCandidates() {
        assertThrows(IllegalArgumentException.class,
                () -> TerminalSessionManager.resolveShellCandidates("not-a-shell"));
    }

    @Test
    void exposesSystemDefaultAndInstalledShells() {
        Map<String, Object> capabilities = TerminalSessionManager.capabilities();
        assertNotNull(capabilities.get("os"));
        assertTrue(((java.util.List<?>) capabilities.get("shells")).stream()
                .map(option -> (Map<?, ?>) option)
                .anyMatch(option -> "system".equals(option.get("id")) && Boolean.TRUE.equals(option.get("available"))));
    }

    @Test
    void terminalThemeOverridesNoColorWithoutLeavingAConflict() {
        Map<String, String> environment = new HashMap<>();
        environment.put("NO_COLOR", "");

        assertTrue(TerminalSessionManager.applyColorEnvironment(environment));
        assertFalse(environment.containsKey("NO_COLOR"));
        assertEquals("1", environment.get("FORCE_COLOR"));
        assertEquals("1", environment.get("CLICOLOR_FORCE"));
        assertEquals("1", environment.get("CLICOLOR"));
    }

    @Test
    void enablesCompatibleColorHints() {
        Map<String, String> environment = new HashMap<>();

        assertTrue(TerminalSessionManager.applyColorEnvironment(environment));
        assertEquals("1", environment.get("CLICOLOR"));
        assertNotNull(environment.get("LSCOLORS"));
        assertNotNull(environment.get("LS_COLORS"));
        assertEquals("1", environment.get("FORCE_COLOR"));
        assertEquals("1", environment.get("CLICOLOR_FORCE"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void batchesLargeOutputAndWaitsForAcknowledgement() throws Exception {
        List<String> batches = new CopyOnWriteArrayList<>();
        List<Long> sequences = new CopyOnWriteArrayList<>();
        TerminalSessionManager.setEventPublisherForTests((sessionId, actionType, message) -> {
            if (actionType != ActionTypeEnum.TERMINAL_OUTPUT) {
                return;
            }
            batches.add((String) message.get("data"));
            long sequence = ((Number) message.get("sequence")).longValue();
            sequences.add(sequence);
            TerminalSessionManager.acknowledgeOutput(sessionId, sequence);
        });

        Map<String, Object> session = TerminalSessionManager.create(directory, 80, 24);
        String sessionId = (String) session.get("sessionId");
        try {
            TerminalSessionManager.attach(sessionId, "test-consumer");
            TerminalSessionManager.write(sessionId, "head -c 70000 /dev/zero | tr '\\0' x\n");
            int outputCharacters = 0;
            for (int attempt = 0; attempt < 100 && outputCharacters < 70_000; attempt++) {
                Thread.sleep(50);
                outputCharacters = batches.stream()
                        .mapToInt(batch -> (int) batch.chars().filter(value -> value == 'x').count())
                        .sum();
            }

            assertTrue(outputCharacters >= 70_000);
            assertTrue(batches.size() >= 3);
            assertTrue(batches.stream().allMatch(batch -> batch.length() <= 32 * 1024));
            for (int index = 1; index < sequences.size(); index++) {
                assertTrue(sequences.get(index) > sequences.get(index - 1));
            }
        } finally {
            TerminalSessionManager.kill(sessionId);
        }
    }

    @Test
    void windowsSystemShellsFallBackPwshThenPowerShellThenCmd() {
        List<TerminalSessionManager.ShellCommand> candidates =
                TerminalSessionManager.resolveSystemShellCandidates("windows 11", (os, names) -> {
                    for (String name : names) {
                        if (name.contains("pwsh")) {
                            return "C:/pwsh.exe";
                        }
                        if (name.contains("powershell")) {
                            return "C:/powershell.exe";
                        }
                        if (name.equals("cmd.exe")) {
                            return "C:/Windows/System32/cmd.exe";
                        }
                    }
                    return null;
                });
        assertEquals(
                List.of("PowerShell 7", "Windows PowerShell", "Command Prompt"),
                candidates.stream().map(TerminalSessionManager.ShellCommand::displayName)
                        .collect(java.util.stream.Collectors.toList()));
        assertTrue(candidates.stream().allMatch(candidate -> "system".equals(candidate.id())));
        assertEquals(TerminalSessionManager.ShellFamily.CMD,
                candidates.get(candidates.size() - 1).family());
    }

    @Test
    void windowsSystemShellsKeepCmdWhenPowershellIsUnavailable() {
        List<TerminalSessionManager.ShellCommand> candidates =
                TerminalSessionManager.resolveSystemShellCandidates("windows 11", (os, names) -> null);

        assertEquals(1, candidates.size());
        assertEquals("system", candidates.get(0).id());
        assertEquals("Command Prompt", candidates.get(0).displayName());
        assertEquals(TerminalSessionManager.ShellFamily.CMD, candidates.get(0).family());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsSystemShellFallsBackToCmdWhenPowershellSpawnBlocked() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        TerminalSessionManager.ShellCommand blocked = new TerminalSessionManager.ShellCommand(
                "system",
                List.of("powershell.exe"),
                "Windows PowerShell",
                TerminalSessionManager.ShellFamily.POWERSHELL
        );
        TerminalSessionManager.ShellCommand fallback = new TerminalSessionManager.ShellCommand(
                "system",
                List.of(System.getenv().getOrDefault("ComSpec", "cmd.exe")),
                "Command Prompt",
                TerminalSessionManager.ShellFamily.CMD
        );
        Map<String, Object> session = TerminalSessionManager.create(
                directory,
                80,
                24,
                List.of(blocked, fallback),
                (command, dir, env, columns, rows) -> {
                    attempts.incrementAndGet();
                    if (!command[0].toLowerCase(Locale.ROOT).contains("cmd")) {
                        throw new IOException("blocked");
                    }
                    return TerminalSessionManager.defaultProcessFactory(command, dir, env, columns, rows);
                }
        );
        String sessionId = (String) session.get("sessionId");
        try {
            assertNotNull(sessionId);
            assertEquals(2, attempts.get());
            assertEquals("Command Prompt", session.get("shell"));
            assertEquals("system", session.get("shellId"));
            assertTrue((Boolean) TerminalSessionManager.status(sessionId).get("alive"));
        } finally {
            TerminalSessionManager.kill(sessionId);
        }
    }

    @Test
    void fallsBackToNextShellWhenSpawnFails() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        TerminalSessionManager.ShellCommand blocked = new TerminalSessionManager.ShellCommand(
                "blocked", List.of("powershell.exe"), "Windows PowerShell", TerminalSessionManager.ShellFamily.POWERSHELL);
        TerminalSessionManager.ShellCommand fallback =
                TerminalSessionManager.resolveShellCandidates("system").get(0);
        Map<String, Object> session = TerminalSessionManager.create(
                directory,
                80,
                24,
                List.of(blocked, fallback),
                (command, dir, env, columns, rows) -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new IOException("powershell blocked by security software");
                    }
                    return TerminalSessionManager.defaultProcessFactory(command, dir, env, columns, rows);
                }
        );
        String sessionId = (String) session.get("sessionId");
        try {
            assertNotNull(sessionId);
            assertEquals("system", session.get("shellId"));
            assertEquals(2, attempts.get());
            assertTrue((Boolean) TerminalSessionManager.status(sessionId).get("alive"));
        } finally {
            TerminalSessionManager.kill(sessionId);
        }
    }

    @Test
    void throwsWhenNoCandidateCanStart() {
        AtomicInteger attempts = new AtomicInteger();
        TerminalSessionManager.ShellCommand blocked = new TerminalSessionManager.ShellCommand(
                "blocked", List.of("powershell.exe"), "Windows PowerShell", TerminalSessionManager.ShellFamily.POWERSHELL);
        TerminalSessionManager.ShellCommand cmd = new TerminalSessionManager.ShellCommand(
                "cmd", List.of("cmd.exe"), "Command Prompt", TerminalSessionManager.ShellFamily.CMD);
        IOException exception = assertThrows(IOException.class,
                () -> TerminalSessionManager.create(
                        directory,
                        80,
                        24,
                        List.of(blocked, cmd),
                        (command, dir, env, columns, rows) -> {
                            attempts.incrementAndGet();
                            throw new IOException("blocked");
                        }
                ));
        assertEquals("blocked", exception.getMessage());
        assertEquals(2, attempts.get());
    }

    @Test
    void doesNotRetryShellsWhenWorkingDirectoryIsUnavailable() {
        AtomicInteger attempts = new AtomicInteger();
        TerminalSessionManager.ShellCommand shell = new TerminalSessionManager.ShellCommand(
                "shell", List.of("shell"), "Shell", TerminalSessionManager.ShellFamily.OTHER);

        assertThrows(IOException.class,
                () -> TerminalSessionManager.create(
                        directory.resolve("missing"),
                        80,
                        24,
                        List.of(shell, shell),
                        (command, dir, env, columns, rows) -> {
                            attempts.incrementAndGet();
                            throw new IOException("should not start");
                        }
                ));
        assertEquals(0, attempts.get());
    }
}

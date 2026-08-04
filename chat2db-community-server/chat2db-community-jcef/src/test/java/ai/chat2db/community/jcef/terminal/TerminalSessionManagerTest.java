package ai.chat2db.community.jcef.terminal;

import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalSessionManagerTest {
    @TempDir
    Path directory;

    @AfterEach
    void resetPublisher() {
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
    void reportsRunningChildProcessAsBusy() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
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
    void batchesLargeOutputAndWaitsForAcknowledgement() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
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
}

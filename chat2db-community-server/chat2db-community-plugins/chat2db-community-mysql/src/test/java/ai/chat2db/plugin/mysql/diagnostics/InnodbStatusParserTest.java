package ai.chat2db.plugin.mysql.diagnostics;

import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbDeadlockSummary;
import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbDeadlockTransaction;
import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbStatusResponse;
import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbStatusSection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InnodbStatusParserTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void parsesMysql57SectionsAndLatestDeadlock() throws IOException {
        String rawText = fixture("mysql57-deadlock.txt");
        InnodbStatusResponse response = InnodbStatusParser.parse(rawText);

        assertSameLineCount(rawText, response.getRawText());
        assertFalse(response.getRawText().contains("secret-value"));
        assertTrue(response.getRawText().contains("UPDATE orders SET status='paid', password=<redacted> WHERE id=1"));
        assertTrue(response.getSections().stream()
                .map(InnodbStatusSection::getNormalizedTitle)
                .anyMatch("LATEST DETECTED DEADLOCK"::equals));
        assertTrue(response.getSections().stream().allMatch(InnodbStatusSection::isRecognized));

        InnodbDeadlockSummary deadlock = response.getLatestDeadlock();
        assertTrue(deadlock.isFound());
        assertEquals("2026-08-31 10:14:59 0x70000", deadlock.getTime());
        assertEquals("1", deadlock.getVictimTransaction());
        assertEquals(2, deadlock.getTransactions().size());

        InnodbDeadlockTransaction victim = deadlock.getTransactions().get(0);
        assertTrue(victim.isVictim());
        assertEquals("12345", victim.getTransactionId());
        assertEquals(4, victim.getActiveSeconds());
        assertEquals("11", victim.getMysqlThreadId());
        assertEquals("91", victim.getQueryId());
        assertEquals("UPDATE orders SET status='paid', password=<redacted> WHERE id=1", victim.getSql());
        assertEquals(1, victim.getHeldLocks().size());
        assertEquals(1, victim.getWaitedLocks().size());
        assertFalse(deadlock.getRawText().contains("secret-value"));
        assertTrue(deadlock.getRawText().contains("UPDATE orders SET status='paid', password=<redacted> WHERE id=1"));
        assertResponseDoesNotLeak(response, "secret-value");
    }

    @Test
    void keepsMysql80RawTextWhenNoLatestDeadlockExists() throws IOException {
        String rawText = fixture("mysql80-no-deadlock.txt");
        InnodbStatusResponse response = InnodbStatusParser.parse(rawText);

        assertEquals(rawText, response.getRawText());
        assertFalse(response.getLatestDeadlock().isFound());
        assertEquals("The server did not provide a latest deadlock.", response.getLatestDeadlock().getMessage());
        assertTrue(response.getSections().stream()
                .map(InnodbStatusSection::getNormalizedTitle)
                .anyMatch("TRANSACTIONS"::equals));
    }

    @Test
    void marksUnknownAndTruncatedContentWithoutDroppingText() throws IOException {
        String rawText = fixture("unknown-truncated.txt");
        InnodbStatusResponse response = InnodbStatusParser.parse(rawText);

        assertSameLineCount(rawText, response.getRawText());
        assertFalse(response.getRawText().contains("secret-token"));
        assertTrue(response.getMessages().stream().anyMatch(message -> "UNKNOWN_SECTION".equals(message.getCode())));
        assertTrue(response.getMessages().stream().anyMatch(message -> "POSSIBLY_TRUNCATED".equals(message.getCode())));
        assertTrue(response.getMessages().stream().anyMatch(message -> "DEADLOCK_VICTIM_NOT_FOUND".equals(message.getCode())));
        assertTrue(response.getLatestDeadlock().isFound());
        assertEquals("UPDATE api_keys SET token=<redacted> WHERE id=9",
                response.getLatestDeadlock().getTransactions().get(0).getSql());
        assertTrue(response.getRawText().contains("UPDATE api_keys SET token=<redacted> WHERE id=9"));
        assertResponseDoesNotLeak(response, "secret-token");
    }

    @Test
    void redactsIdentifiedByValuesFromEveryApiVisibleField() throws JsonProcessingException {
        InnodbStatusResponse response = InnodbStatusParser.parse("""
                ========================
                2026-08-31 12:00:00
                INNODB MONITOR OUTPUT
                ========================
                ------------------------
                LATEST DETECTED DEADLOCK
                ------------------------
                2026-08-31 11:59:59
                *** (1) TRANSACTION:
                TRANSACTION 30001, ACTIVE 1 sec
                MySQL thread id 88, OS thread handle 1408, query id 808 localhost app creating
                CREATE USER 'reader'@'%' IDENTIFIED BY 'reader-secret'
                *** WE ROLL BACK TRANSACTION (1)
                """);

        assertTrue(response.getRawText().contains("CREATE USER 'reader'@'%' IDENTIFIED BY <redacted>"));
        assertTrue(response.getSections().get(1).getText().contains("IDENTIFIED BY <redacted>"));
        assertEquals("CREATE USER 'reader'@'%' IDENTIFIED BY <redacted>",
                response.getLatestDeadlock().getTransactions().get(0).getSql());
        assertResponseDoesNotLeak(response, "reader-secret");
    }

    @Test
    void returnsDiagnosticMessageForUnknownFormat() {
        InnodbStatusResponse response = InnodbStatusParser.parse("unstructured engine output");

        assertEquals("unstructured engine output", response.getRawText());
        assertEquals(1, response.getSections().size());
        assertFalse(response.getSections().get(0).isRecognized());
        assertTrue(response.getMessages().stream().anyMatch(message -> "UNKNOWN_FORMAT".equals(message.getCode())));
        assertFalse(response.getLatestDeadlock().isFound());
    }

    @Test
    void returnsDiagnosticMessageForBlankOutput() {
        InnodbStatusResponse response = InnodbStatusParser.parse("");

        assertEquals("", response.getRawText());
        assertNotNull(response.getCapturedAt());
        assertTrue(response.getMessages().stream().anyMatch(message -> "EMPTY_OUTPUT".equals(message.getCode())));
        assertFalse(response.getLatestDeadlock().isFound());
    }

    private static String fixture(String name) throws IOException {
        try (var inputStream = InnodbStatusParserTest.class.getResourceAsStream("/innodb-status/" + name)) {
            assertNotNull(inputStream, "fixture exists: " + name);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertResponseDoesNotLeak(InnodbStatusResponse response, String secret)
            throws JsonProcessingException {
        String serialized = OBJECT_MAPPER.writeValueAsString(response);
        assertFalse(serialized.contains(secret), serialized);
    }

    private static void assertSameLineCount(String rawText, String redactedText) {
        assertEquals(rawText.split("\\R", -1).length, redactedText.split("\\R", -1).length);
    }
}

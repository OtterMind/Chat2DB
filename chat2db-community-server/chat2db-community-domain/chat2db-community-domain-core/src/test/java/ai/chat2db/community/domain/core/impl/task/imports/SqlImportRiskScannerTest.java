package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportAdmissionFinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlImportRiskScannerTest {

    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearThreshold() {
        System.clearProperty("chat2db.task.import.parallel.max-statement-chars");
    }

    @Test
    void detectsOrderSensitiveSemanticsAcrossStatements() throws Exception {
        List<ImportAdmissionFinding> findings = scan("""
                SET @parent_id = 41;
                CREATE TEMPORARY TABLE staged_ids(id BIGINT);
                SET FOREIGN_KEY_CHECKS = 0;
                CREATE TABLE child(id BIGINT, parent_id BIGINT);
                LOCK TABLES child WRITE;
                INSERT INTO staged_ids VALUES (@parent_id);
                INSERT INTO child VALUES (LAST_INSERT_ID(), @parent_id);
                """);

        assertHasCodes(findings, "A1", "A2", "A3", "A4", "A5", "B2");
    }

    @Test
    void ignoresKeywordsInsideCommentsAndStringLiterals() throws Exception {
        List<ImportAdmissionFinding> findings = scan("""
                -- SET @ignored = 1; CREATE TEMPORARY TABLE ignored(id INT);
                INSERT INTO notes(value) VALUES ('LOCK TABLES x; LAST_INSERT_ID()');
                /* SET FOREIGN_KEY_CHECKS = 0; */
                """);

        assertFalse(hasCode(findings, "A1"));
        assertFalse(hasCode(findings, "A2"));
        assertFalse(hasCode(findings, "A3"));
        assertFalse(hasCode(findings, "A5"));
        assertFalse(hasCode(findings, "B2"));
    }

    @Test
    void flagsAnOversizedAtomicStatement() throws Exception {
        System.setProperty("chat2db.task.import.parallel.max-statement-chars", "16");

        List<ImportAdmissionFinding> findings = scan("INSERT INTO t VALUES (1234567890);");

        assertTrue(hasCode(findings, "C1"));
    }

    private List<ImportAdmissionFinding> scan(String sql) throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("import-" + System.nanoTime() + ".sql"),
                sql, StandardCharsets.UTF_8);
        return SqlImportRiskScanner.scan(source.toFile(), StandardCharsets.UTF_8);
    }

    private void assertHasCodes(List<ImportAdmissionFinding> findings, String... codes) {
        for (String code : codes) {
            assertTrue(hasCode(findings, code), "Missing finding " + code);
        }
    }

    private boolean hasCode(List<ImportAdmissionFinding> findings, String code) {
        return findings.stream().anyMatch(item -> code.equals(item.getCode()));
    }
}

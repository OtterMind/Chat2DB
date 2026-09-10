package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ImportAdmissionReport;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportParallelAdmissionTest {

    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearThresholdOverrides() {
        System.clearProperty("chat2db.task.import.parallel.min-bytes");
        System.clearProperty("chat2db.task.import.parallel.min-rows");
        System.clearProperty("chat2db.task.import.parallelism");
    }

    @Test
    void admitsFullyScannedCsvAfterRelationshipConfirmation() throws Exception {
        useOneUnitThresholds();
        ImportAdmissionReport report = ImportParallelAdmission.assess(
                csvSpec("ID,NAME\n1,Alice\n", true, true), columns());

        assertEquals("PARALLEL_SAFE", report.getVerdict());
        assertEquals("ULTRA_FAST", report.getEffectiveMode());
        assertTrue(report.isParallelAllowed());
        assertTrue(report.isFullScan());
        assertEquals(1L, report.getDataRows());
    }

    @Test
    void requiresExplicitRelationshipConfirmation() throws Exception {
        useOneUnitThresholds();
        ImportAdmissionReport report = ImportParallelAdmission.assess(
                csvSpec("ID,NAME\n1,Alice\n", false, true), columns());

        assertEquals("PARALLEL_FORBIDDEN", report.getVerdict());
        assertTrue(hasFinding(report, "R1", "BLOCKER"));
        assertFalse(report.isParallelAllowed());
    }

    @Test
    void confirmationTurnsGeneratedKeyOrderingIntoAnExplainableDegradation() throws Exception {
        useOneUnitThresholds();
        ImportAdmissionReport report = ImportParallelAdmission.assess(
                csvSpec("NAME\nAlice\n", true, false), columns());

        assertEquals("PARALLEL_DEGRADED", report.getVerdict());
        assertTrue(hasFinding(report, "B1", "DEGRADATION"));
        assertTrue(report.isParallelAllowed());
        assertTrue(report.isRelationshipRiskAccepted());
    }

    @Test
    void embeddedCsvNewlineRemainsAHardBlocker() throws Exception {
        useOneUnitThresholds();
        ImportAdmissionReport report = ImportParallelAdmission.assess(
                csvSpec("ID,NAME\n1,\"Alice\nCooper\"\n", true, true), columns());

        assertEquals("PARALLEL_FORBIDDEN", report.getVerdict());
        assertTrue(hasFinding(report, "C3", "BLOCKER"));
    }

    @Test
    void acceptsCaseDistinctQuotedColumnHeaders() throws Exception {
        useOneUnitThresholds();
        Path source = Files.writeString(tempDirectory.resolve("quoted-columns.csv"),
                "ID,id\n1,2\n", StandardCharsets.UTF_8);
        ImportTaskSpec spec = ImportTaskSpec.builder().sourceFile(source.toString()).importFileId("staged")
                .format("CSV").mode("ULTRA_FAST").confirmedNoStrongRelations(true)
                .options(ImportOptions.builder().charset("UTF-8").delimiter(",")
                        .columnMappings(List.of(new ImportColumnMapping("ID", "ID"),
                                new ImportColumnMapping("id", "id")))
                        .build())
                .build();

        ImportAdmissionReport report = ImportParallelAdmission.assess(spec, List.of(
                TableColumn.builder().name("ID").nullable(0).build(),
                TableColumn.builder().name("id").nullable(0).build()));

        assertFalse(hasFinding(report, "D2", "BLOCKER"));
        assertEquals("PARALLEL_SAFE", report.getVerdict());
    }

    @Test
    void smallCsvIsDowngradedBeforeWorkersStart() throws Exception {
        ImportAdmissionReport report = ImportParallelAdmission.assess(
                csvSpec("ID,NAME\n1,Alice\n", true, true), columns());

        assertEquals("PARALLEL_DEGRADED", report.getVerdict());
        assertEquals("STANDARD", report.getEffectiveMode());
        assertTrue(hasFinding(report, "G0", "DEGRADATION"));
    }

    @Test
    void sqlCannotClaimParallelExecutionWithoutAPlanner() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("input.sql"),
                "CREATE TABLE sample(id INT); INSERT INTO sample VALUES (1);", StandardCharsets.UTF_8);
        ImportTaskSpec spec = ImportTaskSpec.builder().sourceFile(source.toString()).importFileId("staged")
                .format("SQL").mode("ULTRA_FAST").confirmedNoStrongRelations(true).build();

        ImportAdmissionReport report = ImportParallelAdmission.assess(spec, List.of());

        assertEquals("PARALLEL_FORBIDDEN", report.getVerdict());
        assertTrue(hasFinding(report, "P0", "BLOCKER"));
        assertTrue(hasFinding(report, "A4", "BLOCKER"));
    }

    @Test
    void blocksWhenMysqlConnectionHeadroomCannotCoverWorkersAndReserve() throws Exception {
        useOneUnitThresholds();
        System.setProperty("chat2db.task.import.parallelism", "4");
        ImportResourceSnapshot resources = new ImportResourceSnapshot(
                true, 10, 5, true, false, null, true, 0, true, true, "fixture");

        ImportAdmissionReport report = ImportParallelAdmission.assess(
                csvSpec("ID,NAME\n1,Alice\n", true, true), columns(), resources);

        assertTrue(hasFinding(report, "E2", "BLOCKER"));
        assertEquals("PARALLEL_FORBIDDEN", report.getVerdict());
    }

    @Test
    void recordsAcceptedTriggerRiskAndUnknownHostDiskAsDegradations() throws Exception {
        useOneUnitThresholds();
        ImportResourceSnapshot resources = new ImportResourceSnapshot(
                true, 100, 2, true, false, null, true, 3, false, false, "disk unavailable");

        ImportAdmissionReport report = ImportParallelAdmission.assess(
                csvSpec("ID,NAME\n1,Alice\n", true, true), columns(), resources);

        assertTrue(hasFinding(report, "A6", "DEGRADATION"));
        assertTrue(hasFinding(report, "E1U", "DEGRADATION"));
        assertEquals("PARALLEL_DEGRADED", report.getVerdict());
        assertTrue(report.isParallelAllowed());
    }

    @Test
    void blocksAReplicaWhoseLagExceedsTheConfiguredLimit() throws Exception {
        useOneUnitThresholds();
        ImportResourceSnapshot resources = new ImportResourceSnapshot(
                true, 100, 2, true, true, 31L, true, 0, true, true, "fixture");

        ImportAdmissionReport report = ImportParallelAdmission.assess(
                csvSpec("ID,NAME\n1,Alice\n", true, true), columns(), resources);

        assertTrue(hasFinding(report, "E3", "BLOCKER"));
    }

    @Test
    void degradesWhenTriggerMetadataCannotBeVerified() throws Exception {
        useOneUnitThresholds();
        ImportResourceSnapshot resources = new ImportResourceSnapshot(
                true, 100, 2, true, false, null, false, 0, true, true,
                "trigger metadata unavailable");

        ImportAdmissionReport report = ImportParallelAdmission.assess(
                csvSpec("ID,NAME\n1,Alice\n", true, true), columns(), resources);

        assertTrue(hasFinding(report, "A6U", "DEGRADATION"));
        assertEquals("PARALLEL_DEGRADED", report.getVerdict());
    }

    private ImportTaskSpec csvSpec(String content, boolean confirmed, boolean mapGeneratedKey) throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("input-" + System.nanoTime() + ".csv"),
                content, StandardCharsets.UTF_8);
        List<ImportColumnMapping> mappings = mapGeneratedKey
                ? List.of(new ImportColumnMapping("ID", "ID"), new ImportColumnMapping("NAME", "NAME"))
                : List.of(new ImportColumnMapping("NAME", "NAME"));
        return ImportTaskSpec.builder().sourceFile(source.toString()).importFileId("staged")
                .format("CSV").mode("ULTRA_FAST").confirmedNoStrongRelations(confirmed)
                .options(ImportOptions.builder().charset("UTF-8").delimiter(",")
                        .columnMappings(mappings).build())
                .build();
    }

    private List<TableColumn> columns() {
        return List.of(
                TableColumn.builder().name("ID").primaryKey(true).autoIncrement(true).nullable(0).build(),
                TableColumn.builder().name("NAME").nullable(0).build());
    }

    private boolean hasFinding(ImportAdmissionReport report, String code, String severity) {
        return report.getFindings().stream()
                .anyMatch(item -> code.equals(item.getCode()) && severity.equals(item.getSeverity()));
    }

    private void useOneUnitThresholds() {
        System.setProperty("chat2db.task.import.parallel.min-bytes", "1");
        System.setProperty("chat2db.task.import.parallel.min-rows", "1");
    }
}

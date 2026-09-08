package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvShardPreprocessorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void slicesOnlyAtLogicalRecordsAndPreservesEveryRow() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("source.csv"),
                "\uFEFFID,NAME\n1,Alice\n2,\"Bob, Jr\"\n3,Carol\n4,David\n", StandardCharsets.UTF_8);
        Path staging = tempDirectory.resolve("staging");

        List<ImportManifestShard> shards = CsvShardPreprocessor.preprocess(source.toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, staging, "CUSTOMER", 0, 28L);

        assertTrue(shards.size() > 1);
        assertEquals(4L, shards.stream().mapToLong(ImportManifestShard::getEstimatedRows).sum());
        assertTrue(shards.stream().allMatch(shard -> shard.getExpectedChecksum().startsWith("CRC32:")));
        List<List<String>> rows = new ArrayList<>();
        for (ImportManifestShard shard : shards) {
            assertFalse(Files.exists(Path.of(shard.getSourcePath() + ".part")));
            try (CSVParser parser = CSVParser.parse(Path.of(shard.getSourcePath()),
                    StandardCharsets.UTF_8, CSVFormat.DEFAULT)) {
                List<org.apache.commons.csv.CSVRecord> records = parser.getRecords();
                assertEquals(List.of("ID", "NAME"), records.get(0).toList());
                records.stream().skip(1).map(org.apache.commons.csv.CSVRecord::toList).forEach(rows::add);
            }
        }
        assertEquals(List.of(List.of("1", "Alice"), List.of("2", "Bob, Jr"),
                List.of("3", "Carol"), List.of("4", "David")), rows);
    }

    @Test
    void refusesToOverwriteAnExistingShard() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("source.csv"), "ID\n1\n", StandardCharsets.UTF_8);
        Path staging = Files.createDirectories(tempDirectory.resolve("staging"));
        Files.writeString(staging.resolve("customer-00000.csv"), "owned", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> CsvShardPreprocessor.preprocess(source.toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, staging, "CUSTOMER", 0, 1024L));
        assertEquals("owned", Files.readString(staging.resolve("customer-00000.csv")));
    }
}

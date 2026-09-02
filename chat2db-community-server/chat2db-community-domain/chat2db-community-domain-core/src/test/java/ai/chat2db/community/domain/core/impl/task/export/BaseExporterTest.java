package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskCompression;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseExporterTest {

    @TempDir
    Path temporaryDirectory;

    private static final class RecordingExporter extends BaseExporter {

        private final Map<String, ByteArrayOutputStream> written = new LinkedHashMap<>();

        private RecordingExporter() {
            super(new ExportCellProcessorChain(List.of()), new SqlExecutionPolicyManager(List.of()));
            this.suffix = ".sql";
        }

        @Override
        protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
                java.io.OutputStream output, boolean resuming) throws Exception {
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            capture.write(("data of " + tableName).getBytes(StandardCharsets.UTF_8));
            output.write(capture.toByteArray());
            output.flush();
            written.put(tableName, capture);
        }

        @Override
        public String type() {
            return "sql";
        }
    }

    @Test
    void singleTableExportWritesTheFormatStreamIntoTheArtifactFile() throws Exception {
        RecordingExporter exporter = new RecordingExporter();
        File output = temporaryDirectory.resolve("one.sql").toFile();

        exporter.run(ExportTaskSpec.builder().tableNames(List.of("orders")).build(),
                new NoopContext(), output);

        assertArrayEquals("data of orders".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(output.toPath()));
    }

    @Test
    void multiTableExportStreamsEveryEntryWithoutIntermediateFiles() throws Exception {
        RecordingExporter exporter = new RecordingExporter();
        File output = temporaryDirectory.resolve("tables.zip").toFile();

        exporter.run(ExportTaskSpec.builder().tableNames(List.of("first", "second")).build(),
                new NoopContext(), output);

        Map<String, String> entries = unzip(output);
        assertEquals(Map.of("first.sql", "data of first", "second.sql", "data of second"), entries);
        try (var files = Files.list(temporaryDirectory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".task-export-")));
        }
    }

    @Test
    void gzipCompressionWrapsTheWholeArtifact() throws Exception {
        RecordingExporter exporter = new RecordingExporter();
        File output = temporaryDirectory.resolve("one.sql.gz").toFile();

        exporter.run(ExportTaskSpec.builder().tableNames(List.of("orders"))
                .compression(TaskCompression.GZIP).build(), new NoopContext(), output);

        byte[] compressed = Files.readAllBytes(output.toPath());
        assertTrue(compressed[0] == (byte) 0x1f && compressed[1] == (byte) 0x8b, "gzip magic");
        try (GZIPInputStream gunzip = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            assertArrayEquals("data of orders".getBytes(StandardCharsets.UTF_8),
                    gunzip.readAllBytes());
        }
    }

    @Test
    void cancellationDuringMultiTableExportStopsBeforeTheNextEntry() throws Exception {
        RecordingExporter exporter = new RecordingExporter();
        File output = temporaryDirectory.resolve("tables.zip").toFile();

        assertThrows(TaskCancelledException.class, () -> exporter.run(
                ExportTaskSpec.builder().tableNames(List.of("first", "second")).build(),
                new CancellingContext(3), output));

        try (var files = Files.list(temporaryDirectory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".task-export-")));
        }
    }

    private static Map<String, String> unzip(File archive) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive.toPath()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int length;
                while ((length = zip.read(buffer)) != -1) {
                    content.write(buffer, 0, length);
                }
                entries.put(entry.getName(), content.toString(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static class NoopContext implements TaskExecutionContext {

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void checkCancelled() {
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName,
                String mediaType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }

    private static final class CancellingContext extends NoopContext {

        private final int cancelAtCheck;
        private final AtomicInteger checks = new AtomicInteger();

        private CancellingContext(int cancelAtCheck) {
            this.cancelAtCheck = cancelAtCheck;
        }

        @Override
        public void checkCancelled() {
            if (checks.incrementAndGet() >= cancelAtCheck) {
                throw new TaskCancelledException();
            }
        }
    }
}

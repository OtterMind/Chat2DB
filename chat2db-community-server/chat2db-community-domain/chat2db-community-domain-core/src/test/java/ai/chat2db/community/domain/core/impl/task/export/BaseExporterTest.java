package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaseExporterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void cancellationInterruptsMultiTableZipCompressionAndCleansIntermediateFiles() throws Exception {
        BaseExporter exporter = new BaseExporter(new ExportCellProcessorChain(List.of()),
                new SqlExecutionPolicyManager(List.of())) {
            {
                suffix = ".sql";
            }

            @Override
            protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
                    File file) throws Exception {
                Files.write(file.toPath(), new byte[32 * 1024]);
            }

            @Override
            public String type() {
                return "sql";
            }
        };
        ExportTaskSpec spec = ExportTaskSpec.builder()
                .tableNames(List.of("first", "second"))
                .build();
        File output = temporaryDirectory.resolve("tables.zip").toFile();

        assertThrows(TaskCancelledException.class,
                () -> exporter.run(spec, new CancellingContext(7), output));

        try (var files = Files.list(temporaryDirectory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".task-export-")));
        }
    }

    private static final class CancellingContext implements TaskExecutionContext {

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
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
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
}

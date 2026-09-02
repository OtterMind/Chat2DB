package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A checkpointed export persists a keyset cursor every page; after an interruption a second run
 * continues the same artifact from that cursor without duplicating or losing rows.
 */
class CheckpointResumeExportTest {

    private static final String DB_TYPE = "CHECKPOINT_EXPORT_TEST";

    @TempDir
    Path tempDirectory;

    private Connection connection;
    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() throws Exception {
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return new DefaultMetaService();
            }
        });
        connection = DriverManager.getConnection("jdbc:h2:mem:checkpoint_export");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ITEMS (ID INT PRIMARY KEY, NAME VARCHAR(10))");
            statement.execute("INSERT INTO ITEMS SELECT X, 'name' FROM SYSTEM_RANGE(1, 10)");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
        connection.close();
    }

    @Test
    void interruptedCheckpointedExportResumesWithoutGapsOrDuplicates() throws Exception {
        File artifact = tempDirectory.resolve("items.csv").toFile();
        RecordingContext first = new RecordingContext(2);

        assertThrows(TaskCancelledException.class, () -> new CsvTestExporter()
                .run(spec(), first, artifact));
        assertEquals(1, first.saved.size(), "one checkpoint row per shard");
        assertTrue(first.checkpointCalls >= 2, "at least two checkpoint writes before cancellation");

        RecordingContext second = new RecordingContext(Integer.MAX_VALUE);
        second.resumeStates.addAll(first.saved);
        new CsvTestExporter().run(spec(), second, artifact);

        List<String> lines = Files.readAllLines(artifact.toPath(), StandardCharsets.UTF_8);
        lines.set(0, lines.get(0).replace("﻿", ""));
        String header = lines.get(0);
        List<String> ids = lines.subList(1, lines.size()).stream()
                .map(line -> line.substring(0, line.indexOf(',')))
                .toList();
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), ids);
        assertEquals("ID,NAME", header);
        assertEquals(1, lines.stream().filter(line -> line.equals(header)).count());
    }

    private ExportTaskSpec spec() {
        return ExportTaskSpec.builder()
                .taskType("TABLE_DATA_EXPORT")
                .format("CSV")
                .tableNames(List.of("ITEMS"))
                .checkpointRows(3)
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("ITEMS").build())
                .build();
    }

    private static final class CsvTestExporter extends BaseExporter {

        private CsvTestExporter() {
            super(new ExportCellProcessorChain(List.of()), new SqlExecutionPolicyManager(List.of()));
            this.suffix = ".csv";
        }

        @Override
        public String type() {
            return "csv";
        }

        @Override
        protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
                java.io.OutputStream output, boolean resuming) {
            streamTable(spec, tableName, context, output,
                    (stream, effectiveSpec, effectiveTable, resume) ->
                            new ai.chat2db.community.domain.core.impl.task.export.sink.CsvSink(
                                    stream, true, resume),
                    ExportValueMode.NATIVE, 2,
                    new ExportProgressLogger(context, "CSV", tableName), resuming);
        }
    }

    private static final class RecordingContext implements TaskExecutionContext {

        private final List<ResumeState> saved = new ArrayList<>();
        private final List<ResumeState> resumeStates = new ArrayList<>();
        private final int cancelAfterCheckpoints;
        private int checkpointCalls;


        private RecordingContext(int cancelAfterCheckpoints) {
            this.cancelAfterCheckpoints = cancelAfterCheckpoints;
        }

        @Override
        public Long taskId() {
            return 1L;
        }

        @Override
        public List<ResumeState> resumeStates() {
            return List.copyOf(resumeStates);
        }

        @Override
        public void checkpoint(ResumeState state) {
            checkpointCalls++;
            saved.removeIf(existing -> existing.getShardNo().equals(state.getShardNo()));
            saved.add(state);
            if (checkpointCalls >= cancelAfterCheckpoints) {
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
}

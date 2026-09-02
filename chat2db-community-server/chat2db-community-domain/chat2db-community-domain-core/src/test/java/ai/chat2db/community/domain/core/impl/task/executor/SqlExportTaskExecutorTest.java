package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.ExportScopeTypeEnum;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlExportTaskExecutorTest {

    private static final String TEST_DB_TYPE = "SQL_EXPORT_TASK_TEST";

    private IPlugin previousPlugin;

    private RecordingDbManager dbManager;

    @BeforeEach
    void setUp() {
        dbManager = new RecordingDbManager();
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new TestPlugin(dbManager));
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection());
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void allScopeForcesDataExportWhenRequestContainDataIsFalse(@TempDir Path tempDirectory) {
        RecordingContext context = execute(ExportScopeTypeEnum.ALL, false, List.of(), tempDirectory);

        assertNotNull(context.createdArtifact);
        assertEquals(1, dbManager.exportDatabaseCalls);
        assertTrue(dbManager.containData);
        assertEquals(0, dbManager.exportTableCalls);
        assertEquals(0, dbManager.exportTableDataCalls);
    }

    @Test
    void schemaScopeDisablesDataExportWhenRequestContainDataIsTrue(@TempDir Path tempDirectory) {
        RecordingContext context = execute(ExportScopeTypeEnum.SCHEMA, true, List.of(), tempDirectory);

        assertNotNull(context.createdArtifact);
        assertEquals(1, dbManager.exportDatabaseCalls);
        assertFalse(dbManager.containData);
        assertEquals(0, dbManager.exportTableCalls);
        assertEquals(0, dbManager.exportTableDataCalls);
    }

    @Test
    void tableScopeUsesDataOnlyExport(@TempDir Path tempDirectory) {
        RecordingContext context = execute(ExportScopeTypeEnum.TABLE, true, List.of("orders"), tempDirectory);

        assertNotNull(context.createdArtifact);
        assertEquals(0, dbManager.exportDatabaseCalls);
        assertEquals(0, dbManager.exportTableCalls);
        assertEquals(1, dbManager.exportTableDataCalls);
        assertEquals("orders", dbManager.tableName);
    }

    private RecordingContext execute(ExportScopeTypeEnum scope, boolean containData, List<String> tableNames,
            Path tempDirectory) {
        ExportTaskSpec spec = ExportTaskSpec.builder()
                .taskType(TaskType.SQL_EXPORT.name())
                .scope(scope.name())
                .containData(containData)
                .tableNames(tableNames)
                .suggestedFileName("dump.sql")
                .target(TaskTargetSnapshot.builder()
                        .databaseName("app")
                        .schemaName("public")
                        .build())
                .build();
        RecordingContext context = new RecordingContext(tempDirectory);
        new SqlExportTaskExecutor().execute(spec, context);
        return context;
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(SqlExportTaskExecutorTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isClosed" -> false;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class RecordingContext implements TaskExecutionContext {

        private final Path tempDirectory;

        private ArtifactDraft createdArtifact;

        private RecordingContext(Path tempDirectory) {
            this.tempDirectory = tempDirectory;
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
        public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName, String mediaType) {
            createdArtifact = ArtifactDraft.builder()
                    .role(role)
                    .temporaryFile(tempDirectory.resolve("dump.sql.part").toFile())
                    .targetFile(tempDirectory.resolve("dump.sql").toFile())
                    .mediaType(mediaType)
                    .build();
            return createdArtifact;
        }

        @Override
        public void write(String content) {
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }

    private static final class RecordingDbManager extends DefaultDBManager {

        private int exportDatabaseCalls;
        private int exportTableCalls;
        private int exportTableDataCalls;
        private boolean containData;
        private String tableName;

        @Override
        public void exportDatabase(Connection connection, String databaseName, String schemaName, boolean containData,
                TaskExecutionContext context) {
            exportDatabaseCalls++;
            this.containData = containData;
        }

        @Override
        public void exportTable(Connection connection, String databaseName, String schemaName, String tableName,
                boolean containData, TaskExecutionContext context) {
            exportTableCalls++;
            this.containData = containData;
            this.tableName = tableName;
        }

        @Override
        public void exportTableData(Connection connection, String databaseName, String schemaName, String tableName,
                TaskExecutionContext context) {
            exportTableDataCalls++;
            this.tableName = tableName;
        }
    }

    private static final class TestPlugin implements IPlugin {

        private final DBConfig config = new DBConfig();
        private final IDbManager dbManager;

        private TestPlugin(IDbManager dbManager) {
            this.dbManager = dbManager;
            config.setDbType(TEST_DB_TYPE);
            config.setDefaultDriverConfig(new DriverConfig());
        }

        @Override
        public DBConfig getDBConfig() {
            return config;
        }

        @Override
        public IDbManager getDbManager() {
            return dbManager;
        }
    }
}

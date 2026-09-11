package ai.chat2db.community.start.test.api;

import ai.chat2db.community.domain.api.model.request.db.DbTableCopyRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.core.impl.db.DbTableServiceImpl;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TableIdentifierContractTest {
    private static final List<String[]> NAMES = List.of(
            new String[]{"source data", "target data"},
            new String[]{"select", "order"},
            new String[]{"a`b\"c]d", "n`m\"l]k"},
            new String[]{"`source`", "`target`"},
            new String[]{"\"source\"", "\"target\""},
            new String[]{"[source]", "[target]"},
            new String[]{"a'; DROP TABLE x; --", "b'; DROP TABLE y; --"},
            new String[]{"inventory_material_record_source12345678901", "custom_material_copy"});

    static Stream<Arguments> databases() {
        return Chat2DBContext.PLUGIN_MAP.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> NAMES.stream().map(names -> Arguments.of(entry.getKey(), names[0], names[1])));
    }

    @ParameterizedTest(name = "{0}: copy {1}")
    @MethodSource("databases")
    void copyPassesRawNamesToEveryRegisteredPlugin(String dbType, String source, String target) throws Exception {
        IPlugin plugin = Chat2DBContext.PLUGIN_MAP.get(dbType);
        IDbManager manager = plugin.getDbManager();
        IDbMetaData metadata = spy(plugin.getDbMetaData());
        List<String> executed = new ArrayList<>();
        Connection connection = recordingConnection(executed);
        String owner = manager.getClass().getMethod("copyTable", Connection.class, String.class, String.class,
                String.class, String.class, boolean.class).getDeclaringClass().getSimpleName();

        try (MockedStatic<Chat2DBContext> context = context(plugin, metadata, connection)) {
            String quotedSource = metadata.getSQLIdentifierProcessor().quoteIdentifierAlways(source);
            String quotedTarget = metadata.getSQLIdentifierProcessor().quoteIdentifierAlways(target);
            String defaultSource = metadata.getMetaDataName(source);
            String defaultTarget = metadata.getMetaDataName(target);
            if (owner.equals("SqlServerDBManager")) {
                doReturn("CREATE TABLE " + quotedSource + " ([id] int)").when(metadata)
                        .tableDDL(any(Connection.class), any(TableMetadataRequest.class));
            }
            for (boolean copyData : List.of(false, true)) {
                executed.clear();
                clearInvocations(metadata);
                DbTableCopyRequest request = new DbTableCopyRequest();
                request.setDatabaseName("app");
                request.setSchemaName("scope");
                request.setTableName(source);
                request.setNewName(target);
                request.setCopyData(copyData);
                new DbTableServiceImpl(null).copyTable(request);

                List<String> expected = switch (owner) {
                    case "MysqlDBManager", "SUNDBDBManager" -> List.of(ctas(quotedTarget, quotedSource, copyData));
                    case "DefaultDBManager" -> List.of(ctas(defaultTarget, defaultSource, copyData));
                    case "PostgreSQLDBManager", "KingBaseDBManager" -> List.of("CREATE TABLE \"scope\"." + quotedTarget
                            + " AS TABLE \"scope\"." + quotedSource + (copyData ? " WITH DATA" : " WITH NO DATA"));
                    case "OracleDBManager", "DMDBManager", "OscarBaseDBManager" ->
                            List.of(ctas("\"scope\"." + quotedTarget, "\"scope\"." + quotedSource, copyData));
                    case "ClickHouseDBManager" -> statements(copyData,
                            "CREATE TABLE `scope`." + quotedTarget + " AS `scope`." + quotedSource,
                            "INSERT INTO `scope`." + quotedTarget + " SELECT * FROM `scope`." + quotedSource);
                    case "DB2DBManager", "HiveDBManager" -> statements(copyData,
                            "CREATE TABLE " + quotedTarget + " LIKE " + quotedSource
                                    + (owner.equals("DB2DBManager") ? " INCLUDING INDEXES" : ""),
                            "INSERT INTO " + quotedTarget + " SELECT * FROM " + quotedSource);
                    case "SqlServerDBManager" -> statements(copyData,
                            "CREATE TABLE [scope]." + quotedTarget + " ([id] int)",
                            "INSERT INTO [app].[scope]." + quotedTarget + " ([id]) SELECT [id] FROM [app].[scope]." + quotedSource);
                    case "MongodbDBManager" -> List.of("db.getCollection(" + JSON.toJSONString(target)
                            + ").insertMany(db.getCollection(" + JSON.toJSONString(source) + ").find({}))");
                    default -> throw new AssertionError("Add contract coverage for " + dbType + ": " + owner);
                };
                assertEquals(expected, executed, dbType + ", copyData=" + copyData);
                if (owner.equals("DefaultDBManager")) {
                    verify(metadata).getMetaDataName(source);
                    verify(metadata).getMetaDataName(target);
                } else {
                    verify(metadata, never()).getMetaDataName(any(String[].class));
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}: truncate {1}")
    @MethodSource("databases")
    void truncatePassesRawNamesToEveryRegisteredPlugin(String dbType, String source, String unusedTarget) throws Exception {
        IPlugin plugin = Chat2DBContext.PLUGIN_MAP.get(dbType);
        IDbManager manager = plugin.getDbManager();
        IDbMetaData metadata = spy(plugin.getDbMetaData());
        List<String> executed = new ArrayList<>();
        Connection connection = recordingConnection(executed);
        String owner = manager.getClass().getMethod("truncateTable", Connection.class, String.class,
                String.class, String.class).getDeclaringClass().getSimpleName();

        try (MockedStatic<Chat2DBContext> context = context(plugin, metadata, connection)) {
            String quotedSource = metadata.getSQLIdentifierProcessor().quoteIdentifierAlways(source);
            String defaultSource = metadata.getMetaDataName(source);
            clearInvocations(metadata);
            DbTableQueryRequest request = new DbTableQueryRequest();
            request.setDatabaseName("app");
            request.setSchemaName("scope");
            request.setTableName(source);
            new DbTableServiceImpl(null).truncateTable(request);

            String expected = switch (owner) {
                case "DefaultDBManager" -> "TRUNCATE TABLE " + defaultSource;
                case "MysqlDBManager", "DB2DBManager" -> "TRUNCATE TABLE " + quotedSource;
                case "PostgreSQLDBManager", "OracleDBManager", "DMDBManager", "OscarBaseDBManager" ->
                        "TRUNCATE TABLE \"scope\"." + quotedSource;
                case "ClickHouseDBManager" -> "TRUNCATE TABLE `scope`." + quotedSource;
                case "SqlServerDBManager" -> "TRUNCATE TABLE [app].[scope]." + quotedSource;
                case "MongodbDBManager" -> "db.getCollection(" + JSON.toJSONString(source) + ").deleteMany({})";
                default -> throw new AssertionError("Add contract coverage for " + dbType + ": " + owner);
            };
            assertEquals(List.of(expected), executed, dbType);
            if (owner.equals("DefaultDBManager")) {
                verify(metadata).getMetaDataName(source);
            } else {
                verify(metadata, never()).getMetaDataName(any(String[].class));
            }
        }
    }

    private static String ctas(String target, String source, boolean copyData) {
        return "CREATE TABLE " + target + " AS SELECT * FROM " + source + (copyData ? "" : " WHERE 1=0");
    }

    private static List<String> statements(boolean copyData, String structure, String data) {
        return copyData ? List.of(structure, data) : List.of(structure);
    }

    private static Connection recordingConnection(List<String> executed) throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(statement.execute()).thenAnswer(ignored -> { executed.add(sql); return false; });
            ResultSet columns = mock(ResultSet.class);
            when(columns.next()).thenReturn(true, false);
            when(columns.getString("COLUMN_NAME")).thenReturn("id");
            when(columns.getString("DATA_TYPE")).thenReturn("int");
            when(statement.executeQuery()).thenReturn(columns);
            return statement;
        });
        return connection;
    }

    private static MockedStatic<Chat2DBContext> context(IPlugin plugin, IDbMetaData metadata, Connection connection) {
        MockedStatic<Chat2DBContext> context = mockStatic(Chat2DBContext.class);
        context.when(Chat2DBContext::getDBConfig).thenReturn(plugin.getDBConfig());
        context.when(Chat2DBContext::getDbManager).thenReturn(plugin.getDbManager());
        context.when(Chat2DBContext::getDbMetaData).thenReturn(metadata);
        context.when(Chat2DBContext::getConnection).thenReturn(connection);
        return context;
    }
}

package ai.chat2db.plugin.sqlserver;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlServerDBManagerTest {

    @Test
    void shouldRewriteQualifiedTableDdlAndPreserveCopyableStructure() {
        String ddl = """
                CREATE TABLE [sales].[orders]
                (
                    [id] BIGINT identity,
                    [computed_total] AS ([id] + 1),
                    [version] timestamp,
                    [status] int default ((1)),
                    constraint PK_orders
                    primary key ([id]),
                    constraint FK_orders_parent
                    foreign key ([parent_id])
                    references [sales].[orders] ([id]),
                    constraint FK_orders_customer
                    foreign key ([customer_id])
                    references customers ([id])
                )
                GO
                CREATE NONCLUSTERED INDEX [IX_orders_status]
                 ON [sales].[orders] ([status])
                go
                exec sp_addextendedproperty 'MS_Description',N'index comment','SCHEMA',N'sales','TABLE',N'orders','INDEX',N'IX_orders_status'
                GO
                exec sp_addextendedproperty 'MS_Description',N'constraint comment','SCHEMA',N'sales','TABLE',N'orders','CONSTRAINT',N'PK_orders'
                GO
                """;

        List<String> batches = SqlServerDBManager.prepareCopyDdlBatches(
                ddl, "catalog", "sales", "orders", "orders_copy");

        assertEquals(3, batches.size());
        assertTrue(batches.get(0).startsWith("CREATE TABLE [sales].[orders_copy]"));
        assertTrue(batches.get(0).contains("[id] BIGINT identity"));
        assertTrue(batches.get(0).contains("[computed_total] AS ([id] + 1)"));
        assertTrue(batches.get(0).contains("[version] timestamp"));
        assertTrue(batches.get(0).contains("[status] int default ((1))"));
        assertTrue(batches.get(0).contains("primary key ([id])"));
        assertTrue(batches.get(0).contains("references [sales].[orders_copy] ([id])"));
        assertTrue(batches.get(0).contains("references customers ([id])"));
        assertFalse(batches.get(0).contains("constraint PK_orders"));
        assertFalse(batches.get(0).contains("constraint FK_orders_parent"));
        assertTrue(batches.get(1).contains("INDEX [IX_orders_status]"));
        assertTrue(batches.get(1).contains("ON [sales].[orders_copy] ([status])"));
        assertTrue(batches.get(2).contains("'TABLE',N'orders_copy','INDEX',N'IX_orders_status'"));
        assertFalse(batches.stream().anyMatch(batch -> batch.contains("constraint comment")));
    }

    @Test
    void shouldRewriteCurrentUnqualifiedCreateTableFormat() {
        List<String> batches = SqlServerDBManager.prepareCopyDdlBatches(
                "CREATE TABLE [orders]\n([id] int)\ngo\n",
                "catalog", "sales", "orders", "orders_copy");

        assertEquals(List.of("CREATE TABLE [sales].[orders_copy]\n([id] int)"), batches);
    }

    @Test
    void shouldNotSplitGoInsideStringLiteral() {
        List<String> batches = SqlServerDBManager.splitDdlBatches(
                "exec log_comment N'line one\ngo\nline three'\nGO ; -- batch\nSELECT 1;");

        assertEquals(List.of(
                "exec log_comment N'line one\ngo\nline three'",
                "SELECT 1;"), batches);
    }

    @Test
    void shouldIgnoreQuotesInCommentsAndKeepGoInsideBlockComments() {
        List<String> batches = SqlServerDBManager.splitDdlBatches(
                "SELECT 1; -- user's note\nGO\n"
                        + "SELECT 2; /* owner's block\nGO\nstill a comment */\nGO\nSELECT 3;");

        assertEquals(List.of(
                "SELECT 1; -- user's note",
                "SELECT 2; /* owner's block\nGO\nstill a comment */",
                "SELECT 3;"), batches);
    }

    @Test
    void shouldNotRewriteSelfReferenceTextInsideSqlLiteral() {
        String ddl = """
                CREATE TABLE [sales].[orders]
                (
                    [id] int,
                    [description] AS ('references [orders] ('),
                    constraint FK_orders_parent
                    foreign key ([id])
                    references [sales].[orders] ([id])
                )
                GO
                """;

        String createTable = SqlServerDBManager.prepareCopyDdlBatches(
                ddl, "catalog", "sales", "orders", "orders_copy").get(0);

        assertTrue(createTable.contains("AS ('references [orders] (')"));
        assertTrue(createTable.contains("references [sales].[orders_copy] ([id])"));
    }

    @Test
    void shouldRewriteGeneratedUnquotedSelfReferencesOnlyInSqlCode() {
        String ddl = """
                CREATE TABLE [orders]
                (
                    [id] int,
                    [description] AS ('references sales.orders ('),
                    constraint FK_orders_parent
                    foreign key ([id])
                    references sales.orders ([id]),
                    constraint FK_orders_catalog_parent
                    foreign key ([id])
                    references catalog.sales.orders ([id]),
                    constraint FK_orders_external
                    foreign key ([id])
                    references audit.orders ([id])
                    -- references sales.orders ([id])
                    /* references catalog.sales.orders ([id]) */
                )
                GO
                """;

        String createTable = SqlServerDBManager.prepareCopyDdlBatches(
                ddl, "catalog", "sales", "orders", "orders_copy").get(0);

        assertTrue(createTable.contains("AS ('references sales.orders (')"));
        assertTrue(createTable.contains("references [sales].[orders_copy] ([id])"));
        assertTrue(createTable.contains("references audit.orders ([id])"));
        assertTrue(createTable.contains("-- references sales.orders ([id])"));
        assertTrue(createTable.contains("/* references catalog.sales.orders ([id]) */"));
        assertEquals(2, countOccurrences(createTable, "references [sales].[orders_copy] ([id])"));
    }

    @Test
    void shouldSplitInlineGoFromGeneratedIndexComment() {
        String ddl = """
                CREATE TABLE [orders]
                (
                    [id] int
                )
                go
                CREATE NONCLUSTERED INDEX [IX_orders_id]
                 ON [sales].[orders] ([id] ASC)
                go\texec sp_addextendedproperty 'MS_Description',N'index comment','SCHEMA',N'sales','TABLE',N'orders','INDEX',N'IX_orders_id'
                go
                """;

        List<String> batches = SqlServerDBManager.prepareCopyDdlBatches(
                ddl, "catalog", "sales", "orders", "orders_copy");

        assertEquals(3, batches.size());
        assertTrue(batches.get(1).startsWith("CREATE NONCLUSTERED INDEX [IX_orders_id]"));
        assertTrue(batches.get(1).contains("ON [sales].[orders_copy] ([id] ASC)"));
        assertTrue(batches.get(2).startsWith("exec sp_addextendedproperty"));
        assertTrue(batches.get(2).contains("'TABLE',N'orders_copy','INDEX',N'IX_orders_id'"));
    }

    @Test
    void shouldUseTheSameExplicitColumnsForInsertAndSelect() {
        assertEquals(
                "INSERT INTO [catalog].[sales].[orders_copy] ([id], [name]) "
                        + "SELECT [id], [name] FROM [catalog].[sales].[orders]",
                SqlServerDBManager.buildCopyDataSql(
                        "[catalog].[sales].[orders_copy]", "[id], [name]", "[catalog].[sales].[orders]"));
    }

    @Test
    void shouldExcludeGeneratedColumnsButKeepIdentityAndDefaultBackedColumns() {
        assertFalse(SqlServerDBManager.isCopyableColumn("([quantity] * [price])", "decimal"));
        assertFalse(SqlServerDBManager.isCopyableColumn(null, "timestamp"));
        assertFalse(SqlServerDBManager.isCopyableColumn(null, "rowversion"));
        assertTrue(SqlServerDBManager.isCopyableColumn(null, "bigint"));
        assertTrue(SqlServerDBManager.isCopyableColumn(null, "int"));
    }

    @Test
    void shouldEscapeEveryQualifiedIdentifierPart() {
        assertEquals("[catalog]]archive].[sales].[orders]]2026]",
                SqlServerDBManager.buildFullTableName("catalog]archive", "sales", "orders]2026"));
        assertEquals("[catalog].[sales].[orders]",
                SqlServerDBManager.buildFullTableName("[catalog]", "[sales]", "[orders]"));
    }

    @Test
    void shouldNormalizeMetadataFormattedNamesAtCopyBoundary() throws Exception {
        AtomicReference<TableMetadataRequest> ddlRequest = new AtomicReference<>();
        AtomicReference<List<String>> queryParameters = new AtomicReference<>();
        List<String> preparedSql = new ArrayList<>();
        SqlServerMetaData metadata = new SqlServerMetaData() {
            @Override
            public String tableDDL(Connection connection, TableMetadataRequest request) {
                ddlRequest.set(request);
                return "CREATE TABLE [orders]\n([id] int)\ngo\n";
            }
        };
        String dbType = "SQLSERVER_COPY_TEST";
        IPlugin previousPlugin = Chat2DBContext.PLUGIN_MAP.put(dbType, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return null;
            }

            @Override
            public SqlServerMetaData getDbMetaData() {
                return metadata;
            }
        });
        ConnectInfo context = new ConnectInfo();
        context.setDbType(dbType);
        context.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(context);

        try {
            new SqlServerDBManager().copyTable(
                    recordingConnection(preparedSql, queryParameters),
                    "catalog", "sales", "[orders]", "[orders_copy]", true);

            assertEquals("orders", ddlRequest.get().getTableName());
            assertEquals("CREATE TABLE [sales].[orders_copy]\n([id] int)", preparedSql.get(0));
            assertEquals(List.of("sales", "orders"), queryParameters.get());
        } finally {
            context.setConnection(null);
            Chat2DBContext.removeContext();
            if (previousPlugin == null) {
                Chat2DBContext.PLUGIN_MAP.remove(dbType);
            } else {
                Chat2DBContext.PLUGIN_MAP.put(dbType, previousPlugin);
            }
        }
    }

    @Test
    void shouldTurnIdentityInsertOffAndPreserveTheCopyFailure() {
        List<String> statements = new ArrayList<>();
        RuntimeException insertFailure = new RuntimeException("insert failed");
        RuntimeException offFailure = new RuntimeException("off failed");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> SqlServerDBManager.executeIdentityCopy("[dbo].[orders_copy]", "INSERT DATA", sql -> {
                    statements.add(sql);
                    if ("INSERT DATA".equals(sql)) {
                        throw insertFailure;
                    }
                    if (sql.endsWith(" OFF")) {
                        throw offFailure;
                    }
                }));

        assertSame(insertFailure, thrown);
        assertEquals(List.of(
                "SET IDENTITY_INSERT [dbo].[orders_copy] ON",
                "INSERT DATA",
                "SET IDENTITY_INSERT [dbo].[orders_copy] OFF"), statements);
        assertEquals(List.of(offFailure), List.of(thrown.getSuppressed()));
    }

    @Test
    void shouldAttemptIdentityInsertOffWhenEnablingFails() {
        List<String> statements = new ArrayList<>();
        RuntimeException onFailure = new RuntimeException("on failed");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> SqlServerDBManager.executeIdentityCopy("[dbo].[orders_copy]", "INSERT DATA", sql -> {
                    statements.add(sql);
                    if (sql.endsWith(" ON")) {
                        throw onFailure;
                    }
                }));

        assertSame(onFailure, thrown);
        assertEquals(List.of(
                "SET IDENTITY_INSERT [dbo].[orders_copy] ON",
                "SET IDENTITY_INSERT [dbo].[orders_copy] OFF"), statements);
    }

    private static int countOccurrences(String value, String expected) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(expected, index)) >= 0) {
            count++;
            index += expected.length();
        }
        return count;
    }

    private static Connection recordingConnection(List<String> preparedSql,
                                                  AtomicReference<List<String>> queryParameters) {
        return (Connection) Proxy.newProxyInstance(
                SqlServerDBManagerTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        String sql = (String) args[0];
                        preparedSql.add(sql);
                        List<String> parameters = new ArrayList<>();
                        return preparedStatement(parameters, queryParameters);
                    }
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement preparedStatement(List<String> parameters,
                                                       AtomicReference<List<String>> queryParameters) {
        return (PreparedStatement) Proxy.newProxyInstance(
                SqlServerDBManagerTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setString":
                            int index = (Integer) args[0];
                            while (parameters.size() < index) {
                                parameters.add(null);
                            }
                            parameters.set(index - 1, (String) args[1]);
                            return null;
                        case "execute":
                            return false;
                        case "executeQuery":
                            queryParameters.set(List.copyOf(parameters));
                            return emptyResultSet();
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static ResultSet emptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                SqlServerDBManagerTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> "next".equals(method.getName())
                        ? false : defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
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
}

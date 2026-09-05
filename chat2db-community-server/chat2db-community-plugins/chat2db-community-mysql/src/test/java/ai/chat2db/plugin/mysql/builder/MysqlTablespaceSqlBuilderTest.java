package ai.chat2db.plugin.mysql.builder;

import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.Tablespace;
import ai.chat2db.plugin.mysql.MysqlMetaData;
import ai.chat2db.plugin.mysql.util.MysqlVersionUtils;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.TABLESPACE_DETAIL_SQL_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.TABLESPACE_DETAIL_SQL_TEMPLATE_MYSQL57;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.TABLESPACE_OCCUPYING_TABLES_SQL;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.TABLESPACE_OCCUPYING_TABLES_SQL_MYSQL57;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.TABLESPACES_SQL;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.TABLESPACES_SQL_MYSQL57;

/**
 * Unit tests for {@link MysqlTablespaceSqlBuilder} DDL generation and the table-option emit in
 * {@link MysqlSqlBuilder}, plus {@link MysqlVersionUtils} version gating. No database required.
 */
class MysqlTablespaceSqlBuilderTest {

    @Test
    void shouldBuildCreateTablespaceWithInnoDB() {
        MysqlTablespaceSqlBuilder builder = new MysqlTablespaceSqlBuilder();
        Tablespace tablespace = Tablespace.builder()
                .name("ts_archive")
                .dataFiles(List.of("archive.ibd"))
                .build();

        String sql = builder.buildCreateTablespace(tablespace);

        assertEquals("CREATE TABLESPACE `ts_archive` ADD DATAFILE 'archive.ibd' ENGINE = InnoDB", sql);
    }

    @Test
    void shouldBuildCreateTablespaceWithFileBlockSize() {
        MysqlTablespaceSqlBuilder builder = new MysqlTablespaceSqlBuilder();
        Tablespace tablespace = Tablespace.builder()
                .name("ts_compressed")
                .dataFiles(List.of("compressed.ibd"))
                .fileBlockSize(8192L)
                .build();

        String sql = builder.buildCreateTablespace(tablespace);

        assertEquals(
                "CREATE TABLESPACE `ts_compressed` ADD DATAFILE 'compressed.ibd' FILE_BLOCK_SIZE = 8192  ENGINE = InnoDB",
                sql);
    }

    @Test
    void shouldEscapeDataFilePathWithoutTouchingFilesystem() {
        MysqlTablespaceSqlBuilder builder = new MysqlTablespaceSqlBuilder();
        // A path containing a single quote must be escaped; the application must never canonicalize it.
        Tablespace tablespace = Tablespace.builder()
                .name("ts_q")
                .dataFiles(List.of("/var/lib/mysql/it's.ibd"))
                .build();

        String sql = builder.buildCreateTablespace(tablespace);

        assertEquals("CREATE TABLESPACE `ts_q` ADD DATAFILE '/var/lib/mysql/it''s.ibd' ENGINE = InnoDB", sql);
    }

    @Test
    void shouldBuildDropTablespace() {
        MysqlTablespaceSqlBuilder builder = new MysqlTablespaceSqlBuilder();

        String sql = builder.buildDropTablespace("ts_archive");

        assertEquals("DROP TABLESPACE `ts_archive` ENGINE = InnoDB", sql);
    }

    @Test
    void shouldBuildRenameTablespace() {
        MysqlTablespaceSqlBuilder builder = new MysqlTablespaceSqlBuilder();

        String sql = builder.buildRenameTablespace("ts_old", "ts_new");

        assertEquals("ALTER TABLESPACE `ts_old` RENAME TO `ts_new`", sql);
    }

    @Test
    void shouldRejectInvalidTablespaceName() {
        MysqlTablespaceSqlBuilder builder = new MysqlTablespaceSqlBuilder();
        Tablespace tablespace = Tablespace.builder()
                .name("ts; drop")  // contains a semicolon -> not a valid MySQL name
                .dataFiles(List.of("x.ibd"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateTablespace(tablespace));
    }

    @Test
    void shouldRejectBlankDataFilePath() {
        MysqlTablespaceSqlBuilder builder = new MysqlTablespaceSqlBuilder();
        Tablespace tablespace = Tablespace.builder()
                .name("ts_archive")
                .dataFiles(List.of(""))
                .build();

        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateTablespace(tablespace));
    }

    @Test
    void shouldEmitTablespaceOptionInCreateTable() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        TableColumn column = TableColumn.builder()
                .name("id")
                .columnType("BIGINT")
                .nullable(0)
                .build();
        Table table = Table.builder()
                .databaseName("test_db")
                .name("t1")
                .columnList(List.of(column))
                .engine("InnoDB")
                .tablespace("ts_archive")
                .build();

        String sql = builder.buildCreateTable(table, ai.chat2db.community.domain.api.config.TableBuilderConfig.defaultConfig());

        assertTrue(sql.contains(" TABLESPACE `ts_archive`;"), sql);
    }

    @Test
    void shouldOmitTablespaceOptionWhenBlank() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        TableColumn column = TableColumn.builder()
                .name("id")
                .columnType("BIGINT")
                .nullable(0)
                .build();
        Table table = Table.builder()
                .databaseName("test_db")
                .name("t1")
                .columnList(List.of(column))
                .engine("InnoDB")
                .build();

        String sql = builder.buildCreateTable(table, ai.chat2db.community.domain.api.config.TableBuilderConfig.defaultConfig());

        assertFalse(sql.contains("TABLESPACE"), sql);
    }

    @Test
    void shouldEmitTablespaceMigrationInAlterTable() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table oldTable = Table.builder()
                .databaseName("test_db")
                .name("t1")
                .columnList(List.of())
                .indexList(List.of())
                .tablespace("ts_old")
                .build();
        Table newTable = Table.builder()
                .databaseName("test_db")
                .name("t1")
                .columnList(List.of())
                .indexList(List.of())
                .tablespace("ts_new")
                .build();

        String sql = builder.buildAlterTable(oldTable, newTable);

        assertTrue(sql.contains("TABLESPACE `ts_new`"), sql);
    }

    @Test
    void shouldMoveTableBackToFilePerTableWhenTablespaceIsCleared() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table oldTable = Table.builder()
                .databaseName("test_db")
                .name("t1")
                .columnList(List.of())
                .indexList(List.of())
                .tablespace("ts_old")
                .build();
        Table newTable = Table.builder()
                .databaseName("test_db")
                .name("t1")
                .columnList(List.of())
                .indexList(List.of())
                .build();

        String sql = builder.buildAlterTable(oldTable, newTable);

        assertTrue(sql.contains("TABLESPACE innodb_file_per_table"), sql);
    }

    @Test
    void shouldTreatTablespaceNamesAsCaseSensitive() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table oldTable = Table.builder()
                .databaseName("test_db")
                .name("t1")
                .columnList(List.of())
                .indexList(List.of())
                .tablespace("ts_archive")
                .build();
        Table newTable = Table.builder()
                .databaseName("test_db")
                .name("t1")
                .columnList(List.of())
                .indexList(List.of())
                .tablespace("TS_ARCHIVE")
                .build();

        String sql = builder.buildAlterTable(oldTable, newTable);

        assertTrue(sql.contains("TABLESPACE `TS_ARCHIVE`"), sql);
    }

    @Test
    void shouldUseInformationSchemaDiscoverySupportedByMysql57And80() {
        assertTrue(TABLESPACES_SQL.contains("INFORMATION_SCHEMA.INNODB_TABLESPACES"));
        assertTrue(TABLESPACES_SQL.contains("INFORMATION_SCHEMA.FILES"));
        assertTrue(TABLESPACES_SQL.contains("T.SPACE_TYPE = 'General'"));
        assertTrue(TABLESPACES_SQL.contains("T.NAME <> 'mysql'"));
        assertTrue(TABLESPACE_OCCUPYING_TABLES_SQL.contains("INFORMATION_SCHEMA.INNODB_TABLES"));
        assertTrue(TABLESPACE_OCCUPYING_TABLES_SQL_MYSQL57.contains("INFORMATION_SCHEMA.INNODB_SYS_TABLES"));
        assertEquals(TABLESPACES_SQL.replace("ORDER BY T.NAME", "AND T.NAME = '%s' ORDER BY T.NAME"),
                TABLESPACE_DETAIL_SQL_TEMPLATE);
        assertTrue(TABLESPACES_SQL_MYSQL57.contains("INFORMATION_SCHEMA.INNODB_SYS_TABLESPACES"));
        assertTrue(TABLESPACES_SQL_MYSQL57.contains("T.SPACE_TYPE = 'General'"));
        assertEquals(TABLESPACES_SQL_MYSQL57.replace("ORDER BY T.NAME", "AND T.NAME = '%s' ORDER BY T.NAME"),
                TABLESPACE_DETAIL_SQL_TEMPLATE_MYSQL57);
    }

    @Test
    void shouldDiscoverGeneralTablespaceWhenFileMetadataIsUnavailable() {
        Map<String, Object> row = new HashMap<>();
        row.put("SPACE", 42L);
        row.put("NAME", "ts_archive");
        row.put("ENGINE", "InnoDB");
        row.put("FILE_BLOCK_SIZE", 8192L);
        row.put("FILE_NAME", null);
        row.put("AUTOEXTEND_NEXT_SIZE", 0L);
        row.put("MAXIMUM_SIZE", 0L);
        row.put("EXTENT_SIZE", 0L);
        row.put("INITIAL_SIZE", 0L);
        row.put("STATUS", "NORMAL");
        Connection connection = connectionReturning(resultSet(List.of(row)));

        Tablespace tablespace = new MysqlMetaData().tablespaces(connection).get(0);

        assertEquals("ts_archive", tablespace.getName());
        assertEquals(42L, tablespace.getSpaceId());
        assertEquals(8192L, tablespace.getFileBlockSize());
        assertEquals("NORMAL", tablespace.getStatus());
        assertTrue(tablespace.getDataFiles() == null || tablespace.getDataFiles().isEmpty());
    }

    @Test
    void shouldReadBackTableOccupancy() {
        Map<String, Object> tableRow = new HashMap<>();
        tableRow.put("OBJECT_NAME", "app.orders");
        Map<String, Object> archiveRow = new HashMap<>();
        archiveRow.put("OBJECT_NAME", "app.orders_archive");
        Connection connection = connectionReturning(resultSet(List.of(tableRow, archiveRow)));

        List<String> occupyingTables = new MysqlMetaData().occupyingTables(connection, "ts_archive");

        assertEquals(List.of("app.orders", "app.orders_archive"), occupyingTables);
    }

    @Test
    void shouldPreserveTablespacePlacementAcrossCreateAndMigration() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        TableColumn column = TableColumn.builder().name("id").columnType("BIGINT").nullable(0).build();
        Table original = Table.builder().databaseName("test_db").name("t1").columnList(List.of(column))
                .indexList(List.of()).tablespace("ts_old").build();
        Table migrated = Table.builder().databaseName("test_db").name("t1").columnList(List.of(column))
                .indexList(List.of()).tablespace("ts_new").build();

        assertTrue(builder.buildCreateTable(migrated,
                ai.chat2db.community.domain.api.config.TableBuilderConfig.defaultConfig())
                .contains("TABLESPACE `ts_new`"));
        assertTrue(builder.buildAlterTable(original, migrated).contains("TABLESPACE `ts_new`"));
    }

    @Test
    void shouldGatesRenameByServerVersion() {
        assertTrue(MysqlVersionUtils.supportsGeneralTablespace("5.7.6"));
        assertTrue(MysqlVersionUtils.supportsGeneralTablespace("5.7.44"));
        assertTrue(MysqlVersionUtils.supportsGeneralTablespace("8.0.36"));
        assertFalse(MysqlVersionUtils.supportsGeneralTablespace("5.7.5"));
        assertFalse(MysqlVersionUtils.supportsGeneralTablespace("5.6.51"));
        assertFalse(MysqlVersionUtils.supportsGeneralTablespace(""));

        assertTrue(MysqlVersionUtils.supportsTablespaceRename("8.0.36"));
        assertTrue(MysqlVersionUtils.supportsTablespaceRename("8.4.0"));
        assertTrue(MysqlVersionUtils.supportsTablespaceRename("9.0.0"));
        assertFalse(MysqlVersionUtils.supportsTablespaceRename("5.7.44"));
        assertFalse(MysqlVersionUtils.supportsTablespaceRename(null));
        assertFalse(MysqlVersionUtils.supportsTablespaceRename(""));
        // Tolerate version suffixes.
        assertTrue(MysqlVersionUtils.supportsTablespaceRename("8.0.36-log"));
    }

    private Connection connectionReturning(ResultSet resultSet) {
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) {
                        return true;
                    }
                    if ("getResultSet".equals(method.getName())) {
                        return resultSet;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
        });
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> "prepareStatement".equals(method.getName()) ? statement
                        : defaultValue(method.getReturnType()));
    }

    private ResultSet resultSet(List<Map<String, Object>> rows) {
        final int[] rowIndex = {-1};
        final boolean[] wasNull = {false};
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        return ++rowIndex[0] < rows.size();
                    }
                    if ("getString".equals(method.getName()) || "getLong".equals(method.getName())) {
                        Object value = rows.get(rowIndex[0]).get(args[0]);
                        wasNull[0] = value == null;
                        return "getLong".equals(method.getName()) ? value == null ? 0L : ((Number) value).longValue()
                                : value == null ? null : value.toString();
                    }
                    if ("wasNull".equals(method.getName())) {
                        return wasNull[0];
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return 0;
    }
}

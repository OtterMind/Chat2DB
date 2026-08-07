package ai.chat2db.plugin.mysql.builder;

import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.Tablespace;
import ai.chat2db.plugin.mysql.util.MysqlVersionUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void shouldBuildAlterTablespaceAddDatafile() {
        MysqlTablespaceSqlBuilder builder = new MysqlTablespaceSqlBuilder();

        String sql = builder.buildAlterTablespaceAddDatafile("ts_archive", "archive2.ibd");

        assertEquals("ALTER TABLESPACE `ts_archive` ADD DATAFILE 'archive2.ibd' ENGINE = InnoDB", sql);
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
    void shouldGatesRenameByServerVersion() {
        assertTrue(MysqlVersionUtils.supportsTablespaceRename("8.0.36"));
        assertTrue(MysqlVersionUtils.supportsTablespaceRename("8.4.0"));
        assertTrue(MysqlVersionUtils.supportsTablespaceRename("9.0.0"));
        assertFalse(MysqlVersionUtils.supportsTablespaceRename("5.7.44"));
        assertFalse(MysqlVersionUtils.supportsTablespaceRename(null));
        assertFalse(MysqlVersionUtils.supportsTablespaceRename(""));
        // Tolerate version suffixes.
        assertTrue(MysqlVersionUtils.supportsTablespaceRename("8.0.36-log"));
    }
}

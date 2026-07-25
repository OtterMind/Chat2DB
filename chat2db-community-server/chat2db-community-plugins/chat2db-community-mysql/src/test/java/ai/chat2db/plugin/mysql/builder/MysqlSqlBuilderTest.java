package ai.chat2db.plugin.mysql.builder;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.QueryResponse;
import ai.chat2db.community.domain.api.model.result.ResultOperation;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.plugin.mysql.enums.type.MysqlColumnTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlSqlBuilderTest {

    @Test
    void shouldUseEnumExtentInsteadOfColumnComment() {
        TableColumn column = TableColumn.builder()
                .name("status")
                .columnType("ENUM")
                .extent("('draft','published')")
                .comment("workflow status")
                .build();

        String sql = MysqlColumnTypeEnum.ENUM.buildCreateColumnSql(column);

        assertTrue(sql.contains("('draft','published')"), sql);
    }

    @Test
    void shouldBuildCreateDatabaseWithCharsetAndCollation() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Database database = Database.builder()
                .name("analytics")
                .charset("utf8mb4")
                .collation("utf8mb4_0900_ai_ci")
                .build();

        String sql = builder.database().buildCreateDatabase(database);

        assertEquals("CREATE DATABASE `analytics` DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci", sql);
    }

    @Test
    void shouldDefaultDecimalPrecisionWhenOnlyScaleIsProvided() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table table = Table.builder()
                .databaseName("test_db")
                .name("payment")
                .columnList(List.of(TableColumn.builder()
                        .name("amount")
                        .columnType("DECIMAL")
                        .decimalDigits(4)
                        .build()))
                .indexList(List.of())
                .build();

        String sql = builder.ddl().table().buildCreateTable(table, TableBuilderConfig.defaultConfig());

        assertTrue(sql.contains("`amount` DECIMAL(10,4) "));
    }

    @Test
    void shouldDefaultUnsignedDecimalPrecisionWhenOnlyScaleIsProvided() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table table = Table.builder()
                .databaseName("test_db")
                .name("payment")
                .columnList(List.of(TableColumn.builder()
                        .name("amount")
                        .columnType("DECIMAL UNSIGNED")
                        .decimalDigits(4)
                        .build()))
                .indexList(List.of())
                .build();

        String sql = builder.ddl().table().buildCreateTable(table, TableBuilderConfig.defaultConfig());

        assertTrue(sql.contains("`amount` DECIMAL(10,4)UNSIGNED "));
    }

    @Test
    void shouldKeepBareDecimalWhenPrecisionAndScaleAreNotProvided() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table table = Table.builder()
                .databaseName("test_db")
                .name("payment")
                .columnList(List.of(TableColumn.builder()
                        .name("amount")
                        .columnType("DECIMAL")
                        .build()))
                .indexList(List.of())
                .build();

        String sql = builder.ddl().table().buildCreateTable(table, TableBuilderConfig.defaultConfig());

        assertTrue(sql.contains("`amount` DECIMAL "));
    }

    @Test
    void shouldBuildAlterSqlWhenTableCharsetChanges() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table oldTable = Table.builder()
                .databaseName("test_db")
                .name("settlement_record")
                .charset("utf8")
                .collate("utf8_general_ci")
                .engine("InnoDB")
                .incrementValue(1L)
                .columnList(List.of())
                .indexList(List.of())
                .build();
        Table newTable = Table.builder()
                .databaseName("test_db")
                .name("settlement_record")
                .charset("utf8mb4")
                .collate("utf8mb4_general_ci")
                .engine("InnoDB")
                .incrementValue(1L)
                .columnList(List.of())
                .indexList(List.of())
                .build();

        String sql = builder.ddl().table().buildAlterTable(oldTable, newTable);

        assertEquals("ALTER TABLE `test_db`.`settlement_record`\n"
                + "\tDEFAULT CHARACTER SET=utf8mb4,\n"
                + "\tCOLLATE=utf8mb4_general_ci;", sql);
    }

    @Test
    void shouldBuildAlterSqlWhenTableEngineChanges() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table oldTable = Table.builder()
                .databaseName("test_db")
                .name("settlement_record")
                .charset("utf8mb4")
                .collate("utf8mb4_general_ci")
                .engine("InnoDB")
                .incrementValue(1L)
                .columnList(List.of())
                .indexList(List.of())
                .build();
        Table newTable = Table.builder()
                .databaseName("test_db")
                .name("settlement_record")
                .charset("utf8mb4")
                .collate("utf8mb4_general_ci")
                .engine("MyISAM")
                .incrementValue(1L)
                .columnList(List.of())
                .indexList(List.of())
                .build();

        String sql = builder.ddl().table().buildAlterTable(oldTable, newTable);

        assertEquals("ALTER TABLE `test_db`.`settlement_record`\n"
                + "\tENGINE=MyISAM;", sql);
    }

    @Test
    void shouldBuildAlterSqlWithIndexMethodWhenAddingMysqlIndexes() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table oldTable = Table.builder()
                .databaseName("enterprise_gateway_dev")
                .name("access_control_approval_process")
                .columnList(List.of())
                .indexList(List.of())
                .build();
        Table newTable = Table.builder()
                .databaseName("enterprise_gateway_dev")
                .name("access_control_approval_process")
                .columnList(List.of())
                .indexList(List.of(
                        TableIndex.builder()
                                .name("idx_column1")
                                .type("Normal")
                                .method("BTREE")
                                .editStatus(EditStatusEnum.ADD.name())
                                .columnList(List.of(TableIndexColumn.builder()
                                        .columnName("parent_id")
                                        .ascOrDesc("ASC")
                                        .build()))
                                .build(),
                        TableIndex.builder()
                                .name("idx_column2")
                                .type("Normal")
                                .method("BTREE")
                                .editStatus(EditStatusEnum.ADD.name())
                                .columnList(List.of(TableIndexColumn.builder()
                                        .columnName("id")
                                        .ascOrDesc("ASC")
                                        .build()))
                                .build()))
                .build();

        String sql = builder.ddl().table().buildAlterTable(oldTable, newTable);

        assertEquals("ALTER TABLE `enterprise_gateway_dev`.`access_control_approval_process`\n"
                + "\tADD INDEX `idx_column1` (`parent_id` ASC) USING BTREE,\n"
                + "\tADD INDEX `idx_column2` (`id` ASC) USING BTREE;", sql);
    }

    @Test
    void shouldKeepTargetPositionWhenRenamingAndMovingColumn() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table oldTable = mysqlTable(List.of(
                mysqlVarcharColumn("a", "a", null),
                mysqlVarcharColumn("b", "b", null),
                mysqlVarcharColumn("c", "c", null),
                mysqlVarcharColumn("d", "d", null)));
        Table newTable = mysqlTable(List.of(
                mysqlVarcharColumn("b", "b", null),
                mysqlVarcharColumn("c", "c", null),
                mysqlVarcharColumn("x", "a", EditStatusEnum.MODIFY.name()),
                mysqlVarcharColumn("d", "d", null)));

        String sql = builder.ddl().table().buildAlterTable(oldTable, newTable);

        assertTrue(sql.contains("CHANGE COLUMN `a` `x`"));
        assertTrue(sql.contains("AFTER `c`"), sql);
    }

    @Test
    void shouldMoveRenamedColumnToFirst() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table oldTable = mysqlTable(List.of(
                mysqlVarcharColumn("a", "a", null),
                mysqlVarcharColumn("b", "b", null),
                mysqlVarcharColumn("c", "c", null)));
        Table newTable = mysqlTable(List.of(
                mysqlVarcharColumn("x", "c", EditStatusEnum.MODIFY.name()),
                mysqlVarcharColumn("a", "a", null),
                mysqlVarcharColumn("b", "b", null)));

        String sql = builder.ddl().table().buildAlterTable(oldTable, newTable);

        assertTrue(sql.contains("CHANGE COLUMN `c` `x`"), sql);
        assertTrue(sql.contains(" FIRST"), sql);
    }

    @Test
    void shouldNotAddPositionWhenOnlyRenamingColumn() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table oldTable = mysqlTable(List.of(
                mysqlVarcharColumn("a", "a", null),
                mysqlVarcharColumn("b", "b", null)));
        Table newTable = mysqlTable(List.of(
                mysqlVarcharColumn("x", "a", EditStatusEnum.MODIFY.name()),
                mysqlVarcharColumn("b", "b", null)));

        String sql = builder.ddl().table().buildAlterTable(oldTable, newTable);

        assertTrue(sql.contains("CHANGE COLUMN `a` `x`"), sql);
        assertFalse(sql.contains(" FIRST"), sql);
        assertFalse(sql.contains(" AFTER "), sql);
    }

    private static Table mysqlTable(List<TableColumn> columns) {
        return Table.builder()
                .databaseName("test_db")
                .name("sample_table")
                .columnList(columns)
                .indexList(List.of())
                .build();
    }

    private static TableColumn mysqlVarcharColumn(String name, String oldName, String editStatus) {
        return TableColumn.builder()
                .name(name)
                .oldName(oldName)
                .columnType("VARCHAR")
                .columnSize(255)
                .editStatus(editStatus)
                .build();
    }

    @Test
    void shouldPreserveMultilineStringCellValueWhenBuildingUpdateSqlByQuery() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("MYSQL");
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setDbType("MYSQL");
        connectInfo.setDriverConfig(driverConfig);
        Chat2DBContext.putContext(connectInfo);

        try {
            String multiLineSql = "select \n  * \nfrom \n  ai_chat_message;";
            QueryResponse queryResult = new QueryResponse();
            queryResult.setTableName("ai_chat_message");
            queryResult.setHeaderList(List.of(
                    Header.builder()
                            .name("Row Number")
                            .dataType(DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode())
                            .columnType("BIGINT")
                            .build(),
                    Header.builder()
                            .name("id")
                            .dataType(DataTypeEnum.NUMERIC.getCode())
                            .columnType("BIGINT")
                            .primaryKey(true)
                            .build(),
                    Header.builder()
                            .name("content")
                            .dataType(DataTypeEnum.STRING.getCode())
                            .columnType("VARCHAR")
                            .build()));

            ResultOperation operation = new ResultOperation();
            operation.setType("UPDATE");
            operation.setDataList(List.of("1", "42", multiLineSql));
            operation.setOldDataList(List.of("1", "42", "old"));
            queryResult.setOperations(List.of(operation));

            String sql = new MysqlSqlBuilder().dml().buildByQueryResult(queryResult);

            assertTrue(sql.contains("`content` = 'select \n  * \nfrom \n  ai_chat_message;'"));
            assertTrue(sql.contains("where `id` = 42"));
            assertFalse(sql.contains(" LIMIT 1;"));
            assertFalse(sql.contains("`content` = 'select'"));
        } finally {
            Chat2DBContext.removeContext();
        }
    }

    @Test
    void shouldAppendLimitOneWhenUpdatingTableWithoutPrimaryKey() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("MYSQL");
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setDbType("MYSQL");
        connectInfo.setDriverConfig(driverConfig);
        Chat2DBContext.putContext(connectInfo);

        try {
            QueryResponse queryResult = new QueryResponse();
            queryResult.setTableName("ai_chat_message");
            queryResult.setHeaderList(List.of(
                    Header.builder()
                            .name("Row Number")
                            .dataType(DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode())
                            .columnType("BIGINT")
                            .build(),
                    Header.builder()
                            .name("id")
                            .dataType(DataTypeEnum.NUMERIC.getCode())
                            .columnType("BIGINT")
                            .build(),
                    Header.builder()
                            .name("content")
                            .dataType(DataTypeEnum.STRING.getCode())
                            .columnType("VARCHAR")
                            .build()));

            ResultOperation updateOp = new ResultOperation();
            updateOp.setType("UPDATE");
            updateOp.setDataList(List.of("1", "42", "new"));
            updateOp.setOldDataList(List.of("1", "42", "old"));

            ResultOperation deleteOp = new ResultOperation();
            deleteOp.setType("DELETE");
            deleteOp.setDataList(List.of("1", "42", "old"));
            deleteOp.setOldDataList(List.of("1", "42", "old"));

            queryResult.setOperations(List.of(updateOp, deleteOp));

            String sql = new MysqlSqlBuilder().dml().buildByQueryResult(queryResult);
            assertEquals(2, sql.split(" LIMIT 1;", -1).length - 1);
        } finally {
            Chat2DBContext.removeContext();
        }
    }
}

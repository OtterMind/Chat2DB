package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.mysql.builder.MysqlSqlBuilder;
import ai.chat2db.plugin.mysql.enums.type.MysqlIndexTypeEnum;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlFunctionalIndexTest {

    @Test
    void buildsFunctionalIndexWithExactlyOneExpressionWrapper() {
        TableIndex index = functionalIndex(null);

        assertEquals("INDEX `idx_lower_email` ((lower(`email`)))",
                MysqlIndexTypeEnum.NORMAL.buildIndexScript(index));
    }

    @Test
    void buildsColumnPrefixAndFunctionalPartsAsDistinctKeyParts() {
        TableIndex index = TableIndex.builder()
                .name("idx_name_lower_email")
                .type(MysqlIndexTypeEnum.NORMAL.getName())
                .columnList(List.of(
                        TableIndexColumn.builder().columnName("name").subPart(10L).ascOrDesc("ASC").build(),
                        TableIndexColumn.builder().expression("lower(`email`)").ascOrDesc("DESC").build()))
                .build();

        assertEquals("INDEX `idx_name_lower_email` (`name`(10) ASC,(lower(`email`)) DESC)",
                MysqlIndexTypeEnum.NORMAL.buildIndexScript(index));
    }

    @Test
    void buildsFunctionalIndexModificationAndDrop() {
        TableIndex modified = functionalIndex(EditStatusEnum.MODIFY.name());
        modified.setOldName("idx_old_email");
        TableIndex deleted = functionalIndex(EditStatusEnum.DELETE.name());
        deleted.setOldName("idx_old_email");

        assertEquals("DROP INDEX `idx_old_email`,\nADD INDEX `idx_lower_email` ((lower(`email`)))",
                MysqlIndexTypeEnum.NORMAL.buildModifyIndex(modified));
        assertEquals("DROP INDEX `idx_old_email`", MysqlIndexTypeEnum.NORMAL.buildModifyIndex(deleted));
    }

    @Test
    void rejectsUnsupportedFunctionalIndexExpressionSyntax() {
        assertThrows(IllegalArgumentException.class,
                () -> MysqlIndexTypeEnum.NORMAL.buildIndexScript(functionalIndex("email", null)));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlIndexTypeEnum.NORMAL.buildIndexScript(functionalIndex("lower(`email`); DROP TABLE users", null)));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlIndexTypeEnum.NORMAL.buildIndexScript(functionalIndex("lower(`email`", null)));
    }

    @Test
    void rejectsNondeterministicFunctionalIndexExpression() {
        assertThrows(IllegalArgumentException.class,
                () -> MysqlIndexTypeEnum.NORMAL.buildIndexScript(functionalIndex("rand()", null)));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlIndexTypeEnum.NORMAL.buildIndexScript(functionalIndex("uuid()", null)));
    }

    @Test
    void rejectsFunctionalIndexForUnsupportedIndexTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> MysqlIndexTypeEnum.PRIMARY_KEY.buildIndexScript(functionalIndex(null)));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlIndexTypeEnum.FULLTEXT.buildIndexScript(functionalIndex(null)));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlIndexTypeEnum.SPATIAL.buildIndexScript(functionalIndex(null)));
    }

    @Test
    void gatesFunctionalIndexByMysqlVersionWhenConnectionContextExists() {
        withMysqlVersion("5.7.44", () -> assertThrows(IllegalArgumentException.class,
                () -> MysqlIndexTypeEnum.NORMAL.buildIndexScript(functionalIndex(null))));
        withMysqlVersion("8.0.12", () -> assertThrows(IllegalArgumentException.class,
                () -> MysqlIndexTypeEnum.NORMAL.buildIndexScript(functionalIndex(null))));

        withMysqlVersion("8.0.13", () -> assertEquals("INDEX `idx_lower_email` ((lower(`email`)))",
                MysqlIndexTypeEnum.NORMAL.buildIndexScript(functionalIndex(null))));
    }

    @Test
    void buildsFunctionalIndexInAlterPreviewSqlWhenVersionSupportsIt() {
        withMysqlVersion("8.0.34", () -> {
            ai.chat2db.community.domain.api.model.metadata.Table oldTable =
                    ai.chat2db.community.domain.api.model.metadata.Table.builder()
                            .databaseName("app")
                            .name("users")
                            .columnList(List.of())
                            .indexList(List.of())
                            .build();
            ai.chat2db.community.domain.api.model.metadata.Table newTable =
                    ai.chat2db.community.domain.api.model.metadata.Table.builder()
                            .databaseName("app")
                            .name("users")
                            .columnList(List.of())
                            .indexList(List.of(functionalIndex(EditStatusEnum.ADD.name())))
                            .build();

            assertEquals("ALTER TABLE `app`.`users`\n"
                            + "\tADD INDEX `idx_lower_email` ((lower(`email`)));",
                    new MysqlSqlBuilder().ddl().table().buildAlterTable(oldTable, newTable));
        });
    }

    @Test
    void rejectsFunctionalIndexInAlterPreviewSqlForMysql57() {
        withMysqlVersion("5.7.44", () -> {
            ai.chat2db.community.domain.api.model.metadata.Table oldTable =
                    ai.chat2db.community.domain.api.model.metadata.Table.builder()
                            .databaseName("app")
                            .name("users")
                            .columnList(List.of())
                            .indexList(List.of())
                            .build();
            ai.chat2db.community.domain.api.model.metadata.Table newTable =
                    ai.chat2db.community.domain.api.model.metadata.Table.builder()
                            .databaseName("app")
                            .name("users")
                            .columnList(List.of())
                            .indexList(List.of(functionalIndex(EditStatusEnum.ADD.name())))
                            .build();

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new MysqlSqlBuilder().ddl().table().buildAlterTable(oldTable, newTable));
            assertTrue(error.getMessage().contains("8.0.13"));
        });
    }

    @Test
    void readsBackFunctionalIndexExpressionFromShowIndex() throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("COLUMN_NAME", null);
        values.put("SEQ_IN_INDEX", (short) 1);
        values.put("COLLATION", "A");
        values.put("CARDINALITY", 5L);
        values.put("SUB_PART", 0L);
        values.put("Expression", "lower(`email`)");
        ResultSet resultSet = resultSet(values);

        TableIndexColumn column = MysqlMetaData.toTableIndexColumn(resultSet);

        assertNull(column.getColumnName());
        assertEquals("lower(`email`)", column.getExpression());
    }

    @Test
    void preservesColumnIndexWhenExpressionMetadataIsUnavailable() throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("Column_name", "email");
        values.put("SEQ_IN_INDEX", (short) 1);
        values.put("COLLATION", "A");
        values.put("CARDINALITY", 5L);
        values.put("SUB_PART", 0L);
        ResultSet resultSet = resultSet(values, Set.of("Expression"));

        TableIndexColumn column = MysqlMetaData.toTableIndexColumn(resultSet);

        assertEquals("email", column.getColumnName());
        assertNull(column.getExpression());
    }

    @Test
    void hidesGeneratedColumnsBackingFunctionalIndexesFromTableMetadata() throws Exception {
        ResultSet resultSet = columnResultSet("!hidden!idx_lower_email!0!0", "VIRTUAL GENERATED",
                "lower(`email`)");

        assertNull(MysqlMetaData.toTableColumn(resultSet, "app", "users"));
    }

    @Test
    void keepsVisibleGeneratedColumnsButMarksThemGenerated() throws Exception {
        ResultSet resultSet = columnResultSet("email_lower", "VIRTUAL GENERATED", "lower(`email`)");

        ai.chat2db.community.domain.api.model.metadata.TableColumn column =
                MysqlMetaData.toTableColumn(resultSet, "app", "users");

        assertNotNull(column);
        assertEquals("email_lower", column.getName());
        assertTrue(column.getGeneratedColumn());
    }

    private static TableIndex functionalIndex(String editStatus) {
        return functionalIndex("((lower(`email`)))", editStatus);
    }

    private static TableIndex functionalIndex(String expression, String editStatus) {
        return TableIndex.builder()
                .name("idx_lower_email")
                .type(MysqlIndexTypeEnum.NORMAL.getName())
                .editStatus(editStatus)
                .columnList(List.of(TableIndexColumn.builder().expression(expression).build()))
                .build();
    }

    private static void withMysqlVersion(String version, Runnable action) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("MYSQL");
        connectInfo.setDbVersion(version);
        Chat2DBContext.putContext(connectInfo);
        try {
            action.run();
        } finally {
            Chat2DBContext.removeContext();
        }
    }

    private static ResultSet resultSet(Map<String, Object> values) {
        return resultSet(values, Set.of());
    }

    private static ResultSet resultSet(Map<String, Object> values, Set<String> unavailableStringColumns) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    if ("getString".equals(method.getName())) {
                        if (unavailableStringColumns.contains(arguments[0])) {
                            throw new SQLException("column unavailable: " + arguments[0]);
                        }
                        return values.get(arguments[0]);
                    }
                    if ("getShort".equals(method.getName())) {
                        return (short) 1;
                    }
                    if ("getLong".equals(method.getName())) {
                        return 5L;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet columnResultSet(String columnName, String extra, String generationExpression) {
        Map<String, Object> values = new HashMap<>();
        values.put("COLUMN_NAME", columnName);
        values.put("DATA_TYPE", "varchar");
        values.put("COLUMN_DEFAULT", null);
        values.put("EXTRA", extra);
        values.put("GENERATION_EXPRESSION", generationExpression);
        values.put("COLUMN_COMMENT", "");
        values.put("COLUMN_KEY", "");
        values.put("IS_NULLABLE", "YES");
        values.put("ORDINAL_POSITION", 1);
        values.put("NUMERIC_SCALE", 0);
        values.put("CHARACTER_SET_NAME", "utf8mb4");
        values.put("COLLATION_NAME", "utf8mb4_0900_ai_ci");
        values.put("COLUMN_TYPE", "varchar(255)");
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    if ("getString".equals(method.getName())) {
                        return values.get(arguments[0]);
                    }
                    if ("getInt".equals(method.getName())) {
                        return values.get(arguments[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}

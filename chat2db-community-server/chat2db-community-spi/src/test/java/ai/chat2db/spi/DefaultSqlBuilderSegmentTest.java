package ai.chat2db.spi;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultOperation;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.model.request.TruncateTableRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSqlBuilderSegmentTest {

    private final DefaultSqlBuilder builder = new DefaultSqlBuilder();

    @Test
    void buildsDqlThroughUnifiedSegment() {
        assertEquals("SELECT * FROM app.public.users",
                builder.dql().buildSelectTable("app", "public", "users"));
        assertEquals("SELECT COUNT(1) FROM app.public.users",
                builder.dql().buildSelectCount("app", "public", "users"));
        assertEquals("SELECT * FROM users\n LIMIT 10",
                builder.dql().buildPageLimit(PageLimitRequest.builder()
                        .sql("SELECT * FROM users")
                        .offset(0)
                        .pageSize(10)
                        .build()));
    }

    @Test
    void buildPageLimitClampsInvalidBounds() {
        assertEquals("SELECT 1\n LIMIT 1",
                builder.dql().buildPageLimit(PageLimitRequest.builder()
                        .sql("SELECT 1")
                        .offset(-10)
                        .pageSize(0)
                        .build()));
        assertEquals("SELECT 1\n LIMIT 1 OFFSET 5",
                builder.dql().buildPageLimit(PageLimitRequest.builder()
                        .sql("SELECT 1")
                        .offset(5)
                        .pageSize(-10)
                        .build()));
    }

    @Test
    void buildsDdlThroughUnifiedSegment() {
        Table table = new Table();
        table.setName("users");
        table.setColumnList(List.of());
        Database database = new Database();
        database.setName("app");
        Schema schema = new Schema();
        schema.setName("public");

        assertEquals("CREATE DATABASE app",
                builder.ddl().database().buildCreateDatabase(database));
        assertEquals("USE app",
                builder.ddl().database().buildUseDatabase("app"));
        assertEquals("CREATE SCHEMA public",
                builder.ddl().schema().buildCreateSchema(schema));
        assertEquals("CREATE TABLE \"users\" \n);",
                builder.ddl().table().buildCreateTable(table, TableBuilderConfig.defaultConfig()));
        assertEquals("DROP TABLE app.public.users",
                builder.ddl().table().buildDropTable(new DropTableRequest("app", "public", "users")));
        assertEquals("TRUNCATE TABLE app.public.users",
                builder.ddl().table().buildTruncateTable(new TruncateTableRequest("app", "public", "users")));
    }

    @Test
    void buildCreateTableEscapesCommentQuotesWithoutChangingBackslashes() {
        TableColumn column = new TableColumn();
        column.setName("name");
        column.setTableName("users");
        column.setColumnType("VARCHAR");
        column.setNullable(1);
        column.setComment("O'Brien\\docs");
        Table table = new Table();
        table.setName("users");
        table.setColumnList(List.of(column));

        String sql = builder.ddl().table().buildCreateTable(table, TableBuilderConfig.defaultConfig());

        assertTrue(sql.contains("COMMENT ON COLUMN users.name IS 'O''Brien\\docs';"), "actual sql: <" + sql + ">");
    }

    @Test
    void buildsDmlThroughUnifiedSegment() {
        assertEquals("",
                builder.dml().buildTemplate(null, "INSERT"));
    }

    @Test
    void copyWhereSqlUsesIsNullForAllNullSameColumnValues() throws Exception {
        String whereSql = copyWhereSql(List.of(
                whereOperation(null),
                whereOperation(null)
        ));

        assertEquals("WHERE name IS NULL", whereSql);
    }

    @Test
    void copyWhereSqlKeepsNullPredicateForMixedSameColumnValues() throws Exception {
        String whereSql = copyWhereSql(List.of(
                whereOperation(null),
                whereOperation("Alice")
        ));

        assertEquals("WHERE name IS NULL OR name IN ('Alice')", whereSql);
    }

    @Test
    void copyWhereSqlKeepsLikeAsTheDefaultStringComparison() throws Exception {
        DefaultMetaService stringMetaService = new DefaultMetaService() {
            @Override
            public IValueProcessor getValueProcessor() {
                return new DefaultValueProcessor() {
                    @Override
                    public boolean isStringDataType(String dataType) {
                        return true;
                    }
                };
            }
        };
        String whereSql = copyWhereSql(List.of(whereOperation("Alice%")), stringMetaService);

        assertEquals("WHERE name LIKE 'Alice%'", whereSql);
    }

    private String copyWhereSql(List<ResultOperation> operations) throws Exception {
        return copyWhereSql(operations, new DefaultMetaService());
    }

    private String copyWhereSql(List<ResultOperation> operations, IDbMetaData metaSchema) throws Exception {
        Method method = DefaultSqlBuilder.class.getDeclaredMethod(
                "copyWhereSql", List.class, List.class, IDbMetaData.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(builder, operations, List.of(nameHeader()), metaSchema, "mysql");
    }

    private static ResultOperation whereOperation(String value) {
        ResultOperation operation = new ResultOperation();
        operation.setDataList(Collections.singletonList(value));
        operation.setSelectCols(List.of(0));
        return operation;
    }

    private static Header nameHeader() {
        Header header = new Header();
        header.setName("name");
        header.setColumnType("VARCHAR");
        return header;
    }

    @Test
    void updateSqlReturnsEmptyWhenNoColumnChanged() throws Exception {
        withStubPluginContext();
        try {
            String sql = updateSql(List.of("1", "Alice", "30"), List.of("1", "Alice", "30"));

            assertEquals("", sql);
        } finally {
            cleanupStubPluginContext();
        }
    }

    @Test
    void updateSqlBuildsSetClauseForChangedColumn() throws Exception {
        withStubPluginContext();
        try {
            String sql = updateSql(List.of("1", "Alice", "31"), List.of("1", "Alice", "30"));

            assertTrue(sql.startsWith("UPDATE users set "), "actual sql: <" + sql + ">");
            assertFalse(sql.contains("set where"));
            assertTrue(sql.contains("age ="), "actual sql: <" + sql + ">");
            assertTrue(sql.contains("where name ="), "actual sql: <" + sql + ">");
        } finally {
            cleanupStubPluginContext();
        }
    }

    private String updateSql(List<String> row, List<String> oldRow) throws Exception {
        Method method = DefaultSqlBuilder.class.getDeclaredMethod("getUpdateSql",
                String.class, List.class, List.class, List.class, IDbMetaData.class, List.class, boolean.class);
        method.setAccessible(true);
        List<Header> headers = new ArrayList<>();
        Header rowNumber = new Header();
        rowNumber.setName("row_number");
        headers.add(rowNumber);
        headers.add(nameHeader());
        Header age = new Header();
        age.setName("age");
        age.setColumnType("INT");
        headers.add(age);
        return (String) method.invoke(builder, "users", headers, row, oldRow,
                new DefaultMetaService(), List.of("name"), false);
    }

    private static void withStubPluginContext() {
        Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return new DBConfig();
            }
        });
        ConnectInfo info = new ConnectInfo();
        info.setDbType(TEST_DB_TYPE);
        info.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(info);
    }

    private static void cleanupStubPluginContext() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
    }

    private static final String TEST_DB_TYPE = "TEST_STUB_DB_FOR_UPDATE_SQL";
}

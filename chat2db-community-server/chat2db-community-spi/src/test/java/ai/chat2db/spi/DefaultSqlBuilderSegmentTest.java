package ai.chat2db.spi;

import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultOperation;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.model.request.TruncateTableRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private String copyWhereSql(List<ResultOperation> operations) throws Exception {
        Method method = DefaultSqlBuilder.class.getDeclaredMethod(
                "copyWhereSql", List.class, List.class, IDbMetaData.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(builder, operations, List.of(nameHeader()), new DefaultMetaService(), "mysql");
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
}

package ai.chat2db.plugin.mysql.builder;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.QueryResponse;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.result.ResultOperation;
import ai.chat2db.plugin.mysql.MysqlPlugin;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlJsonCopyTest {
    private static final String TEST_DB_TYPE = "MYSQL";
    private final MysqlSqlBuilder builder = new MysqlSqlBuilder();
    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new MysqlPlugin());
        ConnectInfo info = new ConnectInfo();
        info.setDbType(TEST_DB_TYPE);
        info.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(info);
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
    void copiesEqualityConditionsForEveryJsonKind() {
        for (String value : List.of("{\"a\":1}", "[1,2]", "\"hello\"", "42", "true", "false", "null")) {
            assertEquals("WHERE payload = CAST('" + value + "' AS JSON)",
                    copy("WHERE", "json", value));
        }
    }

    @Test
    void keepsSqlNullDistinctFromJsonNull() {
        assertEquals("WHERE payload IS NULL", copy("WHERE", "JSON", null));
        assertEquals("WHERE payload = CAST('null' AS JSON)", copy("WHERE", "JSON", "null"));
    }

    @Test
    void copiesMultipleJsonValuesWithNullAndDeduplication() {
        QueryResponse response = response("JSON", List.of(
                operation("WHERE", null), operation("WHERE", "{\"a\":1}"),
                operation("WHERE", "[1,2]"), operation("WHERE", "{\"a\":1}")));

        assertEquals("WHERE payload IS NULL OR payload IN (CAST('{\"a\":1}' AS JSON), CAST('[1,2]' AS JSON))",
                builder.buildCopyByQueryResult(response));
    }

    @Test
    void copiesJsonInValuesAsTypedValues() {
        QueryResponse response = response("JSON", List.of(
                operation("WHERE", "{\"a\":1}"), operation("WHERE", "{\"a\":1}"),
                operation("WHERE", "null"), operation("WHERE", null)));

        assertEquals("(CAST('{\"a\":1}' AS JSON), CAST('null' AS JSON), NULL)",
                builder.copyInValuesByQuery(response));
    }

    @Test
    void preservesDialectEscapingInsideJsonCasts() {
        String json = "{\"text\":\"中文😀 O'Brien \\\\docs\"}";
        String expectedValue = "CAST('{\"text\":\"中文😀 O''Brien \\\\\\\\docs\"}' AS JSON)";

        assertEquals("WHERE payload = " + expectedValue, copy("WHERE", "JSON", json));
        assertEquals("(" + expectedValue + ")",
                builder.copyInValuesByQuery(response("JSON", List.of(operation("WHERE", json)))));
    }

    @Test
    void castsJsonInMixedColumnConditions() {
        QueryResponse response = response("JSON", List.of(operation("WHERE", "{\"a\":1}")));
        response.setHeaderList(List.of(header("row", "INT"), header("payload", "JSON"), header("label", "VARCHAR")));
        response.getOperations().get(0).setDataList(List.of("1", "{\"a\":1}", "alpha"));
        response.getOperations().get(0).setSelectCols(List.of(1, 2));

        assertEquals("WHERE payload = CAST('{\"a\":1}' AS JSON) AND label LIKE 'alpha'",
                builder.buildCopyByQueryResult(response).trim());
    }

    @Test
    void copiesUpdateWithoutAPrimaryKeyUsingTypedJsonPredicate() {
        String sql = copy("UPDATE_COPY", "JSON", "{\"a\":1}");

        assertTrue(sql.contains("set payload = '{\"a\":1}'"), sql);
        assertTrue(sql.contains("where `payload` = CAST('{\"a\":1}' AS JSON)"), sql);
        assertFalse(sql.substring(0, sql.indexOf(" where ")).contains("CAST("), sql);
    }

    @Test
    void keepsPrimaryKeyUpdateAndJsonInsertValuesCompatible() {
        QueryResponse response = response("JSON", List.of(operation("UPDATE_COPY", "{\"a\":1}")));
        Header id = header("id", "INT");
        id.setPrimaryKey(true);
        response.setHeaderList(List.of(header("row", "INT"), id, header("payload", "JSON")));
        response.getOperations().get(0).setDataList(List.of("1", "7", "{\"a\":1}"));
        response.getOperations().get(0).setSelectCols(List.of(2));

        String sql = builder.buildCopyByQueryResult(response);
        assertTrue(sql.contains("set payload = '{\"a\":1}'"), sql);
        assertTrue(sql.contains("where `id` = '7'"), sql);
        assertFalse(sql.contains("CAST("), sql);
        String insert = copy("CREATE", "JSON", "{\"a\":1}");
        assertTrue(insert.contains("'{\"a\":1}'"), insert);
        assertFalse(insert.contains("CAST("), insert);
    }

    @Test
    void leavesJsonLookingTextAndExternalTextAsStrings() {
        assertEquals("WHERE payload LIKE '{\"a\":1}'", copy("WHERE", "VARCHAR", "{\"a\":1}"));
        assertEquals("('{\"a\":1}')", builder.copyExternalTextInValues(List.of("{\"a\":1}")));
    }

    @Test
    void sharedRowPredicatesKeepTheOldJsonValueAndSingleRowLimit() {
        ResultOperation update = operation("UPDATE", "{\"a\":2}");
        update.setOldDataList(List.of("1", "{\"a\":1}"));
        ResultOperation delete = operation("DELETE", "{\"a\":1}");
        delete.setOldDataList(List.of("1", "{\"a\":1}"));

        String sql = builder.buildByQueryResult(response("JSON", List.of(update, delete)));

        assertTrue(sql.contains("set `payload` = '{\"a\":2}'"), sql);
        assertEquals(2, sql.split("where `payload` = CAST", -1).length - 1, sql);
        assertFalse(sql.contains("CAST('{\"a\":2}' AS JSON)"), sql);
        assertEquals(2, sql.split(" LIMIT 1;", -1).length - 1, sql);
    }

    private String copy(String type, String columnType, String value) {
        return builder.buildCopyByQueryResult(response(columnType, List.of(operation(type, value)))).trim();
    }

    private QueryResponse response(String columnType, List<ResultOperation> operations) {
        QueryResponse response = new QueryResponse();
        response.setTableName("records");
        response.setHeaderList(List.of(header("row", "INT"), header("payload", columnType)));
        response.setOperations(operations);
        return response;
    }

    private Header header(String name, String columnType) {
        Header header = new Header();
        header.setName(name);
        header.setColumnType(columnType);
        return header;
    }

    private ResultOperation operation(String type, String value) {
        ResultOperation operation = new ResultOperation();
        operation.setType(type);
        operation.setSelectCols(List.of(1));
        operation.setDataList(Arrays.asList("1", value));
        operation.setSelectedCell(ResultCell.of(value));
        return operation;
    }
}

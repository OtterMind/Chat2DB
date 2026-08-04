package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlCompletionGetRequest;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression test for the swapped fallback variable in buildIdentifierResult: for a
 * database-supporting, schema-less engine (supportDatabase=true, supportSchema=false) the
 * current database must come from paramDatabaseName, not paramSchemaName.
 */
class GenericSqlCompletionEngineIdentifierTest {

    private static ISQLIdentifierProcessor identifierProcessor() {
        return (ISQLIdentifierProcessor) Proxy.newProxyInstance(
                GenericSqlCompletionEngineIdentifierTest.class.getClassLoader(),
                new Class<?>[]{ISQLIdentifierProcessor.class},
                (proxy, method, args) -> {
                    if ("removeIdentifierQuote".equals(method.getName())) {
                        return args[0];
                    }
                    if ("quoteIdentifier".equals(method.getName()) && args[0] instanceof String s) {
                        return "\"" + s + "\"";
                    }
                    return null;
                });
    }

    private static IDbMetaData metaDataWithTable(String tableName, String columnName) {
        ISQLIdentifierProcessor processor = identifierProcessor();
        return (IDbMetaData) Proxy.newProxyInstance(
                GenericSqlCompletionEngineIdentifierTest.class.getClassLoader(),
                new Class<?>[]{IDbMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSQLIdentifierProcessor" -> processor;
                    case "databases", "schemas" -> List.of();
                    case "tables" -> {
                        Table table = new Table();
                        table.setName(tableName);
                        yield List.of(table);
                    }
                    case "columns" -> {
                        TableColumn column = new TableColumn();
                        column.setName(columnName);
                        column.setColumnType("int");
                        yield List.of(column);
                    }
                    default -> null;
                });
    }

    private static IDbTableService emptyTableService() {
        return (IDbTableService) Proxy.newProxyInstance(
                GenericSqlCompletionEngineIdentifierTest.class.getClassLoader(),
                new Class<?>[]{IDbTableService.class},
                (proxy, method, args) -> null);
    }

    private static Object buildCompletionInfo(DBConfig dbConfig, IDbMetaData metaData, String tableName)
            throws Exception {
        Class<?> infoClass = Class.forName(
                "ai.chat2db.community.domain.core.impl.db.GenericSqlCompletionEngine$CompletionInfo");
        Method builderMethod = infoClass.getDeclaredMethod("builder");
        builderMethod.setAccessible(true);
        Object builder = builderMethod.invoke(null);
        Class<?> builderClass = builder.getClass();
        for (Object[] call : new Object[][]{
                {"dataSourceId", Long.class, 1L},
                {"dbConfig", DBConfig.class, dbConfig},
                {"metaData", IDbMetaData.class, metaData},
                {"datasourceName", String.class, "ds"},
                {"tableName", String.class, tableName}}) {
            Method setter = builderClass.getMethod((String) call[0], (Class<?>) call[1]);
            setter.setAccessible(true);
            setter.invoke(builder, call[2]);
        }
        Method build = builderClass.getMethod("build");
        build.setAccessible(true);
        return build.invoke(builder);
    }

    @Test
    @SuppressWarnings("unchecked")
    void columnCompletionFallsBackToDatabaseNameWhenSchemaUnsupported() throws Exception {
        DBConfig dbConfig = new DBConfig();
        dbConfig.setDbType("CLICKHOUSE");
        dbConfig.setSupportDatabase(true);
        dbConfig.setSupportSchema(false);

        GenericSqlCompletionEngine engine = new GenericSqlCompletionEngine(emptyTableService());
        Object info = buildCompletionInfo(dbConfig, metaDataWithTable("t1", "c1"), "t1");

        DbSqlCompletionGetRequest param = new DbSqlCompletionGetRequest();
        param.setDataSourceId(1L);
        param.setDatabaseName("testdb");
        param.setSchemaName(null);

        Method buildIdentifierResult = GenericSqlCompletionEngine.class.getDeclaredMethod(
                "buildIdentifierResult", info.getClass(), DbSqlCompletionGetRequest.class);
        buildIdentifierResult.setAccessible(true);
        List<SqlCompletionCandidate> result =
                (List<SqlCompletionCandidate>) buildIdentifierResult.invoke(engine, info, param);

        assertFalse(result == null || result.isEmpty(),
                "column completion must use paramDatabaseName for schema-less engines");
        assertEquals(1, result.size());
        assertEquals("c1", result.get(0).getColumnName());
        assertEquals("testdb", result.get(0).getDatabaseName());
    }
}

package ai.chat2db.plugin.presto;

import ai.chat2db.spi.model.request.TableMetadataRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression tests for plugin:presto-1: {@code tableDDL} previously omitted the leading
 * {@link Connection} parameter, so it overloaded instead of overriding
 * {@code DefaultMetaService.tableDDL(Connection, String, String, String)} and the
 * framework call path hit the base implementation's {@code UnsupportedOperationException}.
 */
class PrestoMetaDataTest {

    private static final String EXPECTED_DDL =
            "CREATE TABLE hive.web.page_views (\n   view_time timestamp,\n   user_id varchar\n)";

    @Test
    void overridesConnectionFirstTableDDL() throws Exception {
        Method method = PrestoMetaData.class.getMethod("tableDDL", Connection.class, String.class, String.class,
                String.class);
        assertEquals(PrestoMetaData.class, method.getDeclaringClass(),
                "tableDDL(Connection, ...) must override DefaultMetaService, not be inherited");
    }

    @Test
    void deadThreeArgOverloadRemoved() {
        assertThrows(NoSuchMethodException.class,
                () -> PrestoMetaData.class.getMethod("tableDDL", String.class, String.class, String.class),
                "the old no-Connection overload had no callers and should not exist");
    }

    @Test
    void frameworkCallPathReturnsDdl() {
        AtomicReference<String> executedSql = new AtomicReference<>();
        Connection connection = stubConnection(EXPECTED_DDL, executedSql);

        TableMetadataRequest request = new TableMetadataRequest();
        request.setDatabaseName("hive");
        request.setSchemaName("web");
        request.setTableName("page_views");

        // This is the path DefaultDBManager.exportTable / DbTableServiceImpl take; it used to
        // land on DefaultMetaService and throw UnsupportedOperationException("table DDL").
        String ddl = new PrestoMetaData().tableDDL(connection, request);

        assertEquals(EXPECTED_DDL, ddl);
        assertEquals("SHOW CREATE TABLE \"hive\".\"web\".\"page_views\"", executedSql.get());
    }

    @Test
    void quotesCatalogSchemaAndTableWithoutLosingEmbeddedQuotes() {
        AtomicReference<String> executedSql = new AtomicReference<>();
        Connection connection = stubConnection(EXPECTED_DDL, executedSql);

        new PrestoMetaData().tableDDL(connection, "hive-prod", "order", "daily\"sales");

        assertEquals("SHOW CREATE TABLE \"hive-prod\".\"order\".\"daily\"\"sales\"", executedSql.get());
    }

    @Test
    void usesDefaultCatalogWhenOnlySchemaIsAvailable() {
        AtomicReference<String> executedSql = new AtomicReference<>();
        Connection connection = stubConnection(EXPECTED_DDL, executedSql);

        new PrestoMetaData().tableDDL(connection, null, "web", "page_views");

        assertEquals("SHOW CREATE TABLE \"web\".\"page_views\"", executedSql.get());
    }

    @Test
    void usesSessionDefaultsWhenCatalogAndSchemaAreBlank() {
        AtomicReference<String> executedSql = new AtomicReference<>();
        Connection connection = stubConnection(EXPECTED_DDL, executedSql);

        new PrestoMetaData().tableDDL(connection, " ", null, "page_views");

        assertEquals("SHOW CREATE TABLE \"page_views\"", executedSql.get());
    }

    @Test
    void rejectsCatalogWithoutSchema() {
        IllegalArgumentException nullSchema = assertThrows(IllegalArgumentException.class,
                () -> new PrestoMetaData().tableDDL(null, "hive", null, "page_views"));
        IllegalArgumentException blankSchema = assertThrows(IllegalArgumentException.class,
                () -> new PrestoMetaData().tableDDL(null, "hive", " ", "page_views"));

        assertEquals("Presto schema name is required when catalog name is provided", nullSchema.getMessage());
        assertEquals("Presto schema name is required when catalog name is provided", blankSchema.getMessage());
    }

    @Test
    void rejectsMissingTableName() {
        assertThrows(IllegalArgumentException.class,
                () -> new PrestoMetaData().tableDDL(null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PrestoMetaData().tableDDL(null, null, null, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new PrestoMetaData().tableDDL(null, null, null, " "));
    }

    private static Connection stubConnection(String ddl, AtomicReference<String> executedSql) {
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(PrestoMetaDataTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> true;
                    case "getString" -> ddl;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PrestoMetaDataTest.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> true;
                    case "getResultSet" -> resultSet;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Connection) Proxy.newProxyInstance(PrestoMetaDataTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        executedSql.set((String) args[0]);
                        yield statement;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}

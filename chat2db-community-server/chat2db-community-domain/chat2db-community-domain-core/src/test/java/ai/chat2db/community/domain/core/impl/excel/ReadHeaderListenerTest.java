package ai.chat2db.community.domain.core.impl.excel;

import ai.chat2db.community.domain.api.model.excel.ExcelCheckResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadHeaderListenerTest {

    private static ExcelCheckResponse.Sheet sheet(String tableName, String headerName, String comment) {
        ExcelCheckResponse.Header header = new ExcelCheckResponse.Header();
        header.setHeaderName(headerName);
        header.setDataType("varchar(32)");
        header.setComment(comment);
        ExcelCheckResponse.Sheet sheet = new ExcelCheckResponse.Sheet();
        sheet.setSheetNo(0);
        sheet.setTableName(tableName);
        sheet.setHeaderList(new java.util.ArrayList<>(List.of(header)));
        return sheet;
    }

    private static ReadHeaderListener listener(ExcelCheckResponse.Sheet sheet, Connection connection) {
        ExcelCheckResponse result = new ExcelCheckResponse();
        result.setSheetList(List.of(sheet));
        return new ReadHeaderListener(result, "import.xlsx", connection, null);
    }

    private static Object invokePrivate(Object target, String method, Class<?>[] types, Object... args)
            throws Exception {
        Method m = target.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @Test
    void buildCreateTableEscapesIdentifiersAndComments() throws Exception {
        ExcelCheckResponse.Sheet sheet = sheet("team\"data", "owner\"name", "O'Brien");
        ReadHeaderListener listener = listener(sheet, null);

        String ddl = (String) invokePrivate(listener, "buildCreateTable",
                new Class<?>[]{ExcelCheckResponse.Sheet.class}, sheet);

        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS \"team\"\"data\""), ddl);
        assertTrue(ddl.contains("\"owner\"\"name\" varchar(32)"), ddl);
        assertTrue(ddl.contains("COMMENT 'O''Brien'"), ddl);

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:read-header-ddl");
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT \"owner\"\"name\" FROM \"team\"\"data\"")) {
                assertFalse(resultSet.next());
            }
        }
    }

    @Test
    void buildSqlEscapesTableAndColumnNames() throws Exception {
        ExcelCheckResponse.Sheet sheet = sheet("team\"data", "owner\"name", null);
        ReadHeaderListener listener = listener(sheet, null);
        invokePrivate(listener, "setCurrentSheet", new Class<?>[]{Integer.class}, 0);
        invokePrivate(listener, "buildSql", new Class<?>[]{Map.class}, Map.of(0, "v"));

        Field sqlListField = ReadHeaderListener.class.getDeclaredField("sqlList");
        sqlListField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> sqlList = (List<String>) sqlListField.get(listener);

        assertEquals(1, sqlList.size());
        assertTrue(sqlList.get(0).startsWith("INSERT INTO \"team\"\"data\" (\"owner\"\"name\")"), sqlList.get(0));
    }

    @Test
    void createTablePropagatesSqlException() throws Exception {
        ExcelCheckResponse.Sheet sheet = sheet("t1", "c1", null);
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:read-header-fail");
        connection.close();
        ReadHeaderListener listener = listener(sheet, connection);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> invokePrivate(listener, "createTable",
                        new Class<?>[]{ExcelCheckResponse.Sheet.class}, sheet));
        assertTrue(thrown.getCause() instanceof IllegalStateException);
    }

    @Test
    void insertDataPropagatesSqlException() throws Exception {
        ExcelCheckResponse.Sheet sheet = sheet("t1", "c1", null);
        ReadHeaderListener listener;
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:read-header-insert-fail")) {
            listener = listener(sheet, connection);
            invokePrivate(listener, "setCurrentSheet", new Class<?>[]{Integer.class}, 0);
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> invokePrivate(listener, "insertData", new Class<?>[]{List.class},
                            List.of("INSERT INTO \"missing_table\" (\"c1\") VALUES ('x')")));
            assertTrue(thrown.getCause() instanceof IllegalStateException);
        }
    }
}

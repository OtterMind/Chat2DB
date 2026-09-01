package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.FIELD_CARDINALITY;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.FIELD_COLLATION;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.FIELD_COLUMN_NAME;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.FIELD_INDEX_COMMENT;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.FIELD_INDEX_TYPE;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.FIELD_KEY_NAME;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.FIELD_NON_UNIQUE;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.FIELD_SEQ_IN_INDEX;
import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.FIELD_SUB_PART;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MysqlMetaDataIndexTest {

    @Test
    void indexesPreservesNullAndPositiveSubPartValues() {
        MysqlMetaData metaData = new MysqlMetaData();
        Connection connection = connectionForRows(List.of(
                indexRow("idx_title", "title", null),
                indexRow("idx_summary", "summary", 12L)));

        List<TableIndex> indexes = metaData.indexes(connection, "app", null, "article");

        assertEquals(2, indexes.size());
        assertNull(indexes.get(0).getColumnList().get(0).getSubPart());
        assertEquals(12L, indexes.get(1).getColumnList().get(0).getSubPart());
    }

    @Test
    void indexesPreservesCompositeSubPartValuesInOrdinalOrder() {
        MysqlMetaData metaData = new MysqlMetaData();
        Connection connection = connectionForRows(List.of(
                indexRow("idx_name_code", "code", 5L, (short) 2),
                indexRow("idx_name_code", "name", 10L, (short) 1)));

        List<TableIndex> indexes = metaData.indexes(connection, "app", null, "article");

        assertEquals(1, indexes.size());
        assertEquals("name", indexes.get(0).getColumnList().get(0).getColumnName());
        assertEquals(10L, indexes.get(0).getColumnList().get(0).getSubPart());
        assertEquals("code", indexes.get(0).getColumnList().get(1).getColumnName());
        assertEquals(5L, indexes.get(0).getColumnList().get(1).getSubPart());
    }

    private static Map<String, Object> indexRow(String indexName, String columnName, Long subPart) {
        return indexRow(indexName, columnName, subPart, (short) 1);
    }

    private static Map<String, Object> indexRow(String indexName, String columnName, Long subPart, short ordinalPosition) {
        return Map.ofEntries(
                Map.entry(FIELD_KEY_NAME, indexName),
                Map.entry(FIELD_NON_UNIQUE, true),
                Map.entry(FIELD_INDEX_TYPE, "BTREE"),
                Map.entry(FIELD_INDEX_COMMENT, ""),
                Map.entry(FIELD_COLUMN_NAME, columnName),
                Map.entry(FIELD_SEQ_IN_INDEX, ordinalPosition),
                Map.entry(FIELD_COLLATION, "A"),
                Map.entry(FIELD_CARDINALITY, 1L),
                Map.entry(FIELD_SUB_PART, subPart == null ? NullValue.INSTANCE : subPart));
    }

    private static Connection connectionForRows(List<Map<String, Object>> rows) {
        return (Connection) Proxy.newProxyInstance(MysqlMetaDataIndexTest.class.getClassLoader(),
                new Class[] {Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        return preparedStatementForRows(rows);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement preparedStatementForRows(List<Map<String, Object>> rows) {
        return (PreparedStatement) Proxy.newProxyInstance(MysqlMetaDataIndexTest.class.getClassLoader(),
                new Class[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> true;
                    case "getResultSet" -> resultSetForRows(rows);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ResultSet resultSetForRows(List<Map<String, Object>> rows) {
        class Cursor {
            int index = -1;
            boolean lastWasNull;
        }
        Cursor cursor = new Cursor();
        return (ResultSet) Proxy.newProxyInstance(MysqlMetaDataIndexTest.class.getClassLoader(),
                new Class[] {ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("next".equals(name)) {
                        cursor.index++;
                        return cursor.index < rows.size();
                    }
                    if ("wasNull".equals(name)) {
                        return cursor.lastWasNull;
                    }
                    if ("getString".equals(name)) {
                        Object value = value(rows, cursor.index, args[0]);
                        cursor.lastWasNull = value == NullValue.INSTANCE;
                        return cursor.lastWasNull ? null : String.valueOf(value);
                    }
                    if ("getBoolean".equals(name)) {
                        Object value = value(rows, cursor.index, args[0]);
                        cursor.lastWasNull = value == NullValue.INSTANCE;
                        return !cursor.lastWasNull && Boolean.TRUE.equals(value);
                    }
                    if ("getShort".equals(name)) {
                        Object value = value(rows, cursor.index, args[0]);
                        cursor.lastWasNull = value == NullValue.INSTANCE;
                        return cursor.lastWasNull ? (short) 0 : ((Number) value).shortValue();
                    }
                    if ("getLong".equals(name)) {
                        Object value = value(rows, cursor.index, args[0]);
                        cursor.lastWasNull = value == NullValue.INSTANCE;
                        return cursor.lastWasNull ? 0L : ((Number) value).longValue();
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object value(List<Map<String, Object>> rows, int index, Object column) {
        return rows.get(index).get(String.valueOf(column));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        return null;
    }

    private enum NullValue {
        INSTANCE
    }
}

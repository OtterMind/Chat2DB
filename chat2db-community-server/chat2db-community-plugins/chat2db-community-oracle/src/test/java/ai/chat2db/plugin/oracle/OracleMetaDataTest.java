package ai.chat2db.plugin.oracle;

import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OracleMetaDataTest {

    @Test
    void buildTablesSqlEscapesSchemaAndTableFiltersAsLiterals() {
        String sql = OracleMetaData.buildTablesSql("SCOTT' OR '1'='1", "O'Brien' OR '1'='1");

        assertEquals("SELECT A.OWNER, A.TABLE_NAME, B.COMMENTS FROM ALL_TABLES A "
                        + "LEFT JOIN ALL_TAB_COMMENTS B ON  A.OWNER = B.OWNER  AND A.TABLE_NAME = B.TABLE_NAME\n"
                        + "where A.OWNER = 'SCOTT'' OR ''1''=''1'  "
                        + "and A.TABLE_NAME = 'O''Brien'' OR ''1''=''1'",
                sql);
        assertFalse(sql.contains("A.TABLE_NAME = 'O'Brien'"));
    }

    @Test
    void appendRoutineSourceTextPreservesOracleLineTerminators() {
        StringBuilder builder = new StringBuilder("CREATE OR REPLACE ");

        OracleMetaData.appendRoutineSourceText(builder, "procedure p_test(\n");
        OracleMetaData.appendRoutineSourceText(builder, "  p_id in number\n");
        OracleMetaData.appendRoutineSourceText(builder, ")\n");

        assertEquals("CREATE OR REPLACE procedure p_test(\n  p_id in number\n)\n", builder.toString());
    }

    @Test
    void appendRoutineSourceTextAddsSeparatorWhenMissing() {
        StringBuilder builder = new StringBuilder("CREATE OR REPLACE ");

        OracleMetaData.appendRoutineSourceText(builder, "procedure p_test(");
        OracleMetaData.appendRoutineSourceText(builder, "  p_id in number");
        OracleMetaData.appendRoutineSourceText(builder, ")");

        assertEquals("CREATE OR REPLACE procedure p_test(\n  p_id in number\n)\n", builder.toString());
    }

    @Test
    void appendRoutineSourceTextPreservesExplicitBlankLines() {
        StringBuilder builder = new StringBuilder();

        OracleMetaData.appendRoutineSourceText(builder, "  update products\n");
        OracleMetaData.appendRoutineSourceText(builder, "\n");
        OracleMetaData.appendRoutineSourceText(builder, "  if sql%rowcount = 0 then\n");

        assertEquals("  update products\n\n  if sql%rowcount = 0 then\n", builder.toString());
    }

    @Test
    void indexesKeepsCompositeFunctionExpressionsAlignedWithTheirPositions() {
        CompositeFunctionIndexFixture fixture = new CompositeFunctionIndexFixture();

        List<TableIndex> indexes = new OracleMetaData().indexes(
                fixture.connection(), "ORCL", "APP", "EMPLOYEE");

        assertEquals(1, indexes.size());
        TableIndex index = indexes.get(0);
        assertEquals("IDX_EMPLOYEE_NAME_FN", index.getName());
        assertEquals(List.of("UPPER(FIRST_NAME)", "LOWER(LAST_NAME)"),
                index.getColumnList().stream().map(TableIndexColumn::getColumnName).toList());
        assertEquals(List.of((short) 1, (short) 2),
                index.getColumnList().stream().map(TableIndexColumn::getOrdinalPosition).toList());
    }

    private static final class CompositeFunctionIndexFixture {

        private Connection connection() {
            return proxy(Connection.class, (proxy, method, args) -> {
                if (!"prepareStatement".equals(method.getName())) {
                    return defaultValue(method.getReturnType());
                }
                String sql = (String) args[0];
                ResultSet resultSet = resultSet(rowsFor(sql));
                return proxy(PreparedStatement.class, (statement, statementMethod, statementArgs) -> switch (
                        statementMethod.getName()) {
                    case "execute" -> true;
                    case "getResultSet" -> resultSet;
                    default -> defaultValue(statementMethod.getReturnType());
                });
            });
        }

        private List<Map<String, Object>> rowsFor(String sql) {
            String normalizedSql = sql.toLowerCase(Locale.ROOT);
            if (!normalizedSql.contains("from all_ind_columns")) {
                return List.of();
            }
            if (normalizedSql.contains("aic.column_position = ex.column_position")) {
                return List.of(
                        indexRow((short) 2, "LOWER(\"LAST_NAME\")"),
                        indexRow((short) 1, "UPPER(\"FIRST_NAME\")")
                );
            }
            return List.of(
                    indexRow((short) 2, "UPPER(\"FIRST_NAME\")"),
                    indexRow((short) 2, "LOWER(\"LAST_NAME\")"),
                    indexRow((short) 1, "UPPER(\"FIRST_NAME\")"),
                    indexRow((short) 1, "LOWER(\"LAST_NAME\")")
            );
        }

        private Map<String, Object> indexRow(short position, String expression) {
            return Map.of(
                    "Key_name", "IDX_EMPLOYEE_NAME_FN",
                    "Column_name", "SYS_NC0000" + position + "$",
                    "Index_type", "FUNCTION-BASED NORMAL",
                    "Unique_name", "NONUNIQUE",
                    "Seq_in_index", position,
                    "Collation", "ASC",
                    "COLUMN_EXPRESSION", expression
            );
        }

        private ResultSet resultSet(List<Map<String, Object>> rows) {
            int[] rowIndex = {-1};
            return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
                case "next" -> ++rowIndex[0] < rows.size();
                case "getString" -> {
                    Object value = rows.get(rowIndex[0]).get((String) args[0]);
                    yield value == null ? null : String.valueOf(value);
                }
                case "getShort" -> ((Number) rows.get(rowIndex[0]).get((String) args[0])).shortValue();
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class) {
            return null;
        }
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }
}

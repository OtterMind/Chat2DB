package ai.chat2db.plugin.mariadb.value;

import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.spi.model.value.JDBCDataValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MariaDBValueProcessorTest {

    private final MariaDBValueProcessor processor = new MariaDBValueProcessor();

    @Test
    void convertJDBCValueStrByTypeEscapesAndQuotesValueOfUnmappedType() {
        JDBCDataValue dataValue = jdbcValue("VARCHAR", Types.VARCHAR, "O'Brien");

        assertEquals("'O''Brien'", processor.convertJDBCValueStrByType(dataValue));
    }

    @Test
    void getJdbcSqlValueStringQuotesPlainStringOfUnmappedType() {
        JDBCDataValue dataValue = jdbcValue("VARCHAR", Types.VARCHAR, "hello");

        assertEquals("'hello'", processor.getJdbcSqlValueString(dataValue));
    }

    @Test
    void convertSQLValueByTypeEmitsNowFunctionBareForFactoryMappedType() {
        assertEquals("now()", processor.convertSQLValueByType(sqlValue("now()", "TIMESTAMP")));
    }

    @Test
    void convertSQLValueByTypeEmitsDefaultKeywordBareForFactoryMappedType() {
        assertEquals("default", processor.convertSQLValueByType(sqlValue("default", "DATETIME")));
    }

    @Test
    void convertSQLValueByTypeMatchesFunctionSetCaseInsensitively() {
        assertEquals("NOW()", processor.convertSQLValueByType(sqlValue("NOW()", "TIMESTAMP")));
    }

    @Test
    void convertSQLValueByTypeStillQuotesOrdinaryTimestampLiteral() {
        assertEquals("'2024-01-02 03:04:05'", processor.convertSQLValueByType(sqlValue("2024-01-02 03:04:05", "TIMESTAMP")));
    }

    private static JDBCDataValue jdbcValue(String columnTypeName, int sqlType, Object value) {
        ResultSet resultSet = proxy(ResultSet.class, (target, method, args) -> {
            if ("getObject".equals(method.getName()) || "getString".equals(method.getName())) {
                return value;
            }
            return defaultValue(method.getReturnType());
        });
        ResultSetMetaData metaData = proxy(ResultSetMetaData.class, (target, method, args) -> switch (method.getName()) {
            case "getColumnTypeName" -> columnTypeName;
            case "getColumnType" -> sqlType;
            default -> defaultValue(method.getReturnType());
        });
        return new JDBCDataValue(resultSet, metaData, 1, false);
    }

    private static SQLDataValue sqlValue(String value, String dataTypeName) {
        DataType dataType = new DataType();
        dataType.setDataTypeName(dataTypeName);
        SQLDataValue sqlDataValue = new SQLDataValue();
        sqlDataValue.setValue(value);
        sqlDataValue.setDataType(dataType);
        return sqlDataValue;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(target, method, args);
            }
            return handler.invoke(target, method, args);
        });
        return type.cast(proxy);
    }

    private static Object invokeObjectMethod(Object target, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> target.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
            case "hashCode" -> System.identityHashCode(target);
            case "equals" -> target == args[0];
            default -> null;
        };
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }
}

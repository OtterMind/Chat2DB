package ai.chat2db.plugin.mysql.completion.value;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

public final class MysqlSqlCompletionValueDefaults {

    private MysqlSqlCompletionValueDefaults() {
    }

    public static String defaultValue(String fieldType) {
        String type = normalizeType(fieldType);
        if (isBoolean(type)) {
            return "FALSE";
        }
        if (isInteger(type)) {
            return "0";
        }
        if (isDecimal(type)) {
            return "0.0";
        }
        if ("DATE".equals(type)) {
            return "CURRENT_DATE";
        }
        if (isDateTime(type)) {
            return "CURRENT_TIMESTAMP";
        }
        if (isTime(type)) {
            return "CURRENT_TIME";
        }
        if ("YEAR".equals(type)) {
            return "YEAR(CURRENT_DATE)";
        }
        if (isBinary(type)) {
            return "X''";
        }
        if ("JSON".equals(type)) {
            return "JSON_OBJECT()";
        }
        if (isString(type)) {
            return "''";
        }
        return "NULL";
    }

    public static String normalizeType(String fieldType) {
        String value = StringUtils.defaultString(fieldType).trim().toUpperCase(Locale.ROOT);
        int spaceIndex = value.indexOf(' ');
        return spaceIndex < 0 ? value : value.substring(0, spaceIndex);
    }

    public static boolean isBoolean(String type) {
        return "BOOL".equals(type) || "BOOLEAN".equals(type) || "TINYINT(1)".equals(type)
                || "BIT(1)".equals(type);
    }

    public static boolean isInteger(String type) {
        return type.matches("(?:TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT|SERIAL)(?:\\(\\d+\\))?");
    }

    public static boolean isDecimal(String type) {
        return type.matches("(?:DECIMAL|DEC|NUMERIC|FLOAT|DOUBLE|REAL)(?:\\(.*\\))?");
    }

    public static boolean isDateTime(String type) {
        return type.startsWith("DATETIME") || type.startsWith("TIMESTAMP");
    }

    public static boolean isTime(String type) {
        return type.startsWith("TIME");
    }

    public static boolean isBinary(String type) {
        return type.matches("(?:BINARY|VARBINARY|TINYBLOB|BLOB|MEDIUMBLOB|LONGBLOB)(?:\\(.*\\))?");
    }

    public static boolean isString(String type) {
        return type.matches("(?:CHAR|VARCHAR|NVARCHAR|TINYTEXT|TEXT|MEDIUMTEXT|LONGTEXT|ENUM|SET)(?:\\(.*\\))?");
    }
}

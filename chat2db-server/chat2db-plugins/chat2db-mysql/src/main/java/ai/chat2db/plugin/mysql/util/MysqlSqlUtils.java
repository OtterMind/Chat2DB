package ai.chat2db.plugin.mysql.util;

import org.apache.commons.lang3.StringUtils;

public final class MysqlSqlUtils {

    private MysqlSqlUtils() {
    }

    public static String quoteString(String value) {
        return "'" + escapeString(value) + "'";
    }

    public static String escapeString(String value) {
        return StringUtils.defaultString(value).replace("\\", "\\\\").replace("'", "''");
    }
}

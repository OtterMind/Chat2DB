package ai.chat2db.spi.util;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlStringUtil {
    private static final Pattern MYSQL_PATTERN = Pattern.compile("[`\"](.*?)[`\"]");
    private static final Pattern SQL_SERVER_PATTERN = Pattern.compile("[\\[\"'](.*?)[]\"]");
    private static final Pattern STANDARD_PATTERN = Pattern.compile("\"(.*?)\"");
    private static final Pattern SINGLE_QUOTED_STRING_PATTERN = Pattern.compile("^'(.*?)'$");
    private static final Pattern NEED_QUIET_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");


    public static String removeQuote(String sql) {
        return removeQuote(sql, null);
    }


    public static String removeQuote(String sql, String dbType) {
        if (StringUtils.isBlank(sql)) {
            return sql;
        }
        if (DatabaseTypeEnum.MYSQL.name().equals(dbType)) {
            return removePattern(sql, MYSQL_PATTERN);
        } else if (DatabaseTypeEnum.SQLSERVER.name().equals(dbType)) {
            return removePattern(sql, SQL_SERVER_PATTERN);
        } else {
            return removePattern(sql, STANDARD_PATTERN);
        }
    }

    public static String removeQuoteAndEscape(String sql) {
        String unquotedSql = removeQuote(sql, null);
        return escapeIdentifiers(unquotedSql, null);
    }
    public static String removeQuoteAndEscape(String sql, String dbType) {
        String unquotedSql = removeQuote(sql, dbType);
        return escapeIdentifiers(unquotedSql, dbType);
    }


    private static String removePattern(String sql, Pattern pattern) {
        Matcher matcher = pattern.matcher(sql);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1)));
        }
        matcher.appendTail(result);
        return result.toString();
    }


    private static String escapeIdentifiers(String sql, String dbType) {
        Matcher matcher = SINGLE_QUOTED_STRING_PATTERN.matcher(sql);
        if (matcher.matches()) {
            String content = matcher.group(1);
            String escapedContent = content.replace("'", "''");
            return "'" + escapedContent + "'";
        } else {
            sql = sql.replace("'", "''");
        }

        return sql;
    }

    public static boolean isValidSqlValue(String value) {
        Matcher matcher = NEED_QUIET_PATTERN.matcher(value);
        return matcher.matches();
    }

    public static String quoteValue(String value, String dbType) {
        if (StringUtils.isBlank(value) || isValidSqlValue(value)) {
            if (StringUtils.equalsAnyIgnoreCase(dbType,
                    DatabaseTypeEnum.ORACLE.name(),
                    DatabaseTypeEnum.OSCAR.name(),
                    DatabaseTypeEnum.DM.name())) {
                if (containsLowerCase(value)) {
                    return "\"" + value + "\"";
                }
            }
            if (StringUtils.equalsAnyIgnoreCase(dbType,
                    DatabaseTypeEnum.POSTGRESQL.name())) {
                if (containsUpperCase(value)) {
                    return "\"" + value + "\"";
                }
            }
            return value;
        }
        if (StringUtils.equalsIgnoreCase(DatabaseTypeEnum.MYSQL.name(), dbType)) {
            return "`" + value + "`";
        } else if (StringUtils.equalsIgnoreCase(DatabaseTypeEnum.SQLSERVER.name(), dbType)) {
            return "[" + value + "]";
        } else {
            return "\"" + value + "\"";
        }
    }

    public static boolean isQuote(String value, String dbType) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        char firstChar = value.charAt(0);
        char lastChar = value.charAt(value.length() - 1);
        if (StringUtils.equalsAnyIgnoreCase(dbType, DatabaseTypeEnum.MYSQL.name())) {
            return firstChar == '`' && lastChar == '`';
        }
        if (StringUtils.equalsIgnoreCase(dbType, DatabaseTypeEnum.SQLSERVER.name())) {
            return firstChar == '[' && lastChar == ']';
        }
        return firstChar == '"' && lastChar == '"';
    }


    public static boolean isQuote(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        return value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"';
    }


    public static boolean containsLowerCase(String value) {
        for (char c : value.toCharArray()) {
            if (Character.isLowerCase(c)) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsUpperCase(String value) {
        for (char c : value.toCharArray()) {
            if (Character.isUpperCase(c)) {
                return true;
            }
        }
        return false;
    }

}

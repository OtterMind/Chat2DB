package ai.chat2db.spi;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultSQLIdentifierProcessor implements ISQLIdentifierProcessor {

    private static final Pattern NEED_QUIET_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");
    private static final Pattern STANDARD_PATTERN = Pattern.compile("\"(.*?)\"");

    @Override
    public boolean isValidIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return true;
        }
        Matcher matcher = NEED_QUIET_PATTERN.matcher(identifier);
        return matcher.matches();
    }

    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return false;
    }

    @Override
    public String quoteIdentifier(String identifier, Integer majorVersion, Integer minorVersion) {
        if (isValidIdentifier(identifier)) {
            return identifier;
        }
        return StringUtils.wrap(identifier, '"');
    }


    @Override
    public String quoteIdentifier(String identifier) {
        if (isValidIdentifier(identifier)) {
            return identifier;
        }
        return StringUtils.wrap(identifier, '"');
    }

    @Override
    public String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String removeIdentifierQuote(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        if (identifier.startsWith("\"") && identifier.endsWith("\"") && identifier.length() >= 2) {
            return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
        }
        return identifier;
    }

    @Override
    public String quoteIdentifierIgnoreCase(String identifier) {
        return quoteIdentifier(identifier);
    }


    protected static String removePattern(String sql, Pattern pattern) {
        Matcher matcher = pattern.matcher(sql);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    protected static boolean containsLowerCase(String value) {
        for (char c : value.toCharArray()) {
            if (Character.isLowerCase(c)) {
                return true;
            }
        }
        return false;
    }

    protected static boolean containsUpperCase(String value) {
        for (char c : value.toCharArray()) {
            if (Character.isUpperCase(c)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isQuoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return false;
        }
        return identifier.startsWith("\"") && identifier.endsWith("\"");
    }

    @Override
    public String convertIdentifierCase(String identifier) {
        return identifier;
    }

    @Override
    public String escapeString(String str) {
        if (StringUtils.isBlank(str)) {
            return str;
        }

        StringBuilder result = new StringBuilder();
        char[] chars = str.toCharArray();
        int length = chars.length;

        for (int i = 0; i < length; i++) {
            char c = chars[i];
            result.append(c);
            if (c == '\'') {
                if (i + 1 < length) {
                    if (chars[i + 1] == '\'') {
                        result.append(chars[++i]);
                    } else {
                        result.append('\'');
                    }
                } else {
                    result.append('\'');
                }
            }
        }

        return result.toString();
    }
}

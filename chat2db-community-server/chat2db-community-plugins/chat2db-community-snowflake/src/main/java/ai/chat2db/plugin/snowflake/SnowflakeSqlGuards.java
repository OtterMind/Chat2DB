package ai.chat2db.plugin.snowflake;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import ai.chat2db.plugin.snowflake.identifier.SnowflakeIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

/**
 * Validation helpers for non-escapable SQL positions in Snowflake DDL generation
 * (engine/charset/collation tokens, raw DEFAULT expressions, index sort direction).
 * Escaping itself lives in {@link SnowflakeIdentifierProcessor}.
 */
public final class SnowflakeSqlGuards {

    private static final Pattern SNOWFLAKE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_$]+$");
    private static final Set<String> DEFAULT_BREAKOUT_KEYWORDS = Set.of(
            "ALTER", "CHECK", "CONSTRAINT", "CREATE", "DEFAULT", "DELETE", "DROP", "GENERATED",
            "GRANT", "INSERT", "MERGE", "PRIMARY", "REFERENCES", "REVOKE", "TRUNCATE", "UNIQUE",
            "UPDATE", "SELECT");
    private static final Set<String> TYPE_BREAKOUT_KEYWORDS = Set.of(
            "CHECK", "COLLATE", "CONSTRAINT", "DEFAULT", "GENERATED", "NOT", "NULL", "PRIMARY",
            "REFERENCES", "UNIQUE");
    private static final Set<String> CLAUSE_BREAKOUT_KEYWORDS = Set.of(
            "ALTER", "CALL", "COPY", "CREATE", "DELETE", "DROP", "GRANT", "INSERT", "MERGE",
            "REVOKE", "TRUNCATE", "UPDATE", "USE");

    private SnowflakeSqlGuards() {
    }

    /**
     * Validate a strict Snowflake name token (ENGINE / CHARACTER SET / COLLATE style positions where
     * escaping is impossible by design).
     */
    public static String requireSnowflakeName(String value, String what) {
        if (value == null || !SNOWFLAKE_NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Snowflake " + what + ": " + value);
        }
        return value;
    }

    /**
     * Validate a quote-aware DEFAULT expression. Quoted literal content is normalized without
     * double-escaping existing doubled quotes. Other expressions may contain nested calls and
     * Snowflake sequence references, but cannot terminate the statement or append a constraint.
     */
    public static String requireDefaultExpression(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("default value", value);
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return "'" + normalizeStringLiteralContent(trimmed.substring(1, trimmed.length() - 1)) + "'";
        }
        scanExpression(trimmed, DEFAULT_BREAKOUT_KEYWORDS, false, "default value");
        return trimmed;
    }

    /**
     * Validate a Snowflake type expression used by metadata when no enum entry is available.
     */
    public static String requireColumnTypeExpression(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("column type", value);
        }
        String expression = value.trim();
        scanExpression(expression, TYPE_BREAKOUT_KEYWORDS, false, "column type");
        return expression;
    }

    /**
     * Validate the optional raw Snowflake table tail supported by this builder.
     */
    public static String requireClusterByClause(String value) {
        String clause = StringUtils.trimToEmpty(value);
        if (!StringUtils.startsWithIgnoreCase(clause, "CLUSTER BY ")) {
            throw invalid("cluster by clause", value);
        }
        String expression = clause.substring("CLUSTER BY ".length()).trim();
        if (expression.isEmpty()) {
            throw invalid("cluster by clause", value);
        }
        scanExpression(expression, CLAUSE_BREAKOUT_KEYWORDS, false, "cluster by clause");
        return "CLUSTER BY " + expression;
    }

    /**
     * Validate an index sort direction: only ASC/DESC are legal, returned in canonical uppercase.
     */
    public static String requireAscOrDesc(String value) {
        String trimmed = StringUtils.trimToEmpty(value);
        if ("ASC".equalsIgnoreCase(trimmed)) {
            return "ASC";
        }
        if ("DESC".equalsIgnoreCase(trimmed)) {
            return "DESC";
        }
        throw new IllegalArgumentException("Invalid Snowflake index sort direction: " + value);
    }

    private static void scanExpression(String expression, Set<String> breakoutKeywords,
                                       boolean allowTopLevelComma, String description) {
        Deque<Character> delimiters = new ArrayDeque<>();
        boolean sawContent = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\'' || c == '"') {
                i = scanQuoted(expression, i, c, description);
                sawContent = true;
                continue;
            }
            if (startsWith(expression, i, "--") || startsWith(expression, i, "/*")
                    || startsWith(expression, i, "*/") || c == ';' || c == '\n' || c == '\r'
                    || Character.isISOControl(c)) {
                throw invalid(description, expression);
            }
            if (c == '(' || c == '[') {
                delimiters.push(c);
                sawContent = true;
                continue;
            }
            if (c == ')' || c == ']') {
                if (delimiters.isEmpty() || !matches(delimiters.pop(), c)) {
                    throw invalid(description, expression);
                }
                sawContent = true;
                continue;
            }
            if (c == ',' && delimiters.isEmpty() && !allowTopLevelComma) {
                throw invalid(description, expression);
            }
            if (Character.isLetter(c) || c == '_') {
                int tokenEnd = scanWord(expression, i);
                String token = expression.substring(i, tokenEnd).toUpperCase(Locale.ROOT);
                if (breakoutKeywords.contains(token)
                        || (delimiters.isEmpty()
                        && (("NOT".equals(token) || "NULL".equals(token)) && sawContent))) {
                    throw invalid(description, expression);
                }
                sawContent = true;
                i = tokenEnd - 1;
                continue;
            }
            if (!Character.isWhitespace(c)) {
                sawContent = true;
            }
        }
        if (!sawContent || !delimiters.isEmpty()) {
            throw invalid(description, expression);
        }
    }

    private static int scanQuoted(String expression, int quoteStart, char quote, String description) {
        for (int i = quoteStart + 1; i < expression.length(); i++) {
            if (expression.charAt(i) == quote) {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == quote) {
                    i++;
                } else {
                    return i;
                }
            }
        }
        throw invalid(description, expression);
    }

    private static int scanWord(String expression, int start) {
        int current = start + 1;
        while (current < expression.length()) {
            char c = expression.charAt(current);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                break;
            }
            current++;
        }
        return current;
    }

    private static String normalizeStringLiteralContent(String content) {
        StringBuilder result = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c != '\'') {
                result.append(c);
                continue;
            }
            result.append("''");
            if (i + 1 < content.length() && content.charAt(i + 1) == '\'') {
                i++;
            }
        }
        return result.toString();
    }

    private static boolean startsWith(String value, int offset, String candidate) {
        return offset + candidate.length() <= value.length() && value.startsWith(candidate, offset);
    }

    private static boolean matches(char open, char close) {
        return open == '(' && close == ')' || open == '[' && close == ']';
    }

    private static IllegalArgumentException invalid(String description, String value) {
        return new IllegalArgumentException("Invalid Snowflake " + description + ": " + value);
    }
}

package ai.chat2db.plugin.hive;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation helpers for non-escapable SQL positions in Hive DDL generation
 * (engine/charset/collation tokens, numeric DEFAULT literals, index sort
 * direction). Escaping itself lives in
 * {@link ai.chat2db.plugin.hive.identifier.HiveIdentifierProcessor}.
 */
public final class HiveSqlGuards {

    private static final Pattern HIVE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final Pattern NUMERIC_DEFAULT_PATTERN = Pattern.compile(
            "^([+-]?(\\d+(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?|0[xX][0-9a-fA-F]+|(?i:TRUE|FALSE))$");
    private static final Set<String> TYPE_BREAKOUT_KEYWORDS = Set.of(
            "ALTER", "CHECK", "CONSTRAINT", "CREATE", "DEFAULT", "DELETE", "DROP", "GENERATED",
            "GRANT", "INSERT", "NOT", "NULL", "PRIMARY", "REFERENCES", "REVOKE", "TRUNCATE",
            "UNIQUE", "UPDATE");
    private static final Set<String> MULTI_WORD_TYPES = Set.of(
            "CHARACTER VARYING", "DOUBLE PRECISION", "TIME WITH LOCAL TIME ZONE", "TIME WITH TIME ZONE",
            "TIMESTAMP WITH LOCAL TIME ZONE", "TIMESTAMP WITH TIME ZONE");

    private HiveSqlGuards() {
    }

    /**
     * Validate a strict Hive name token (ENGINE / CHARACTER SET / COLLATE style positions where
     * escaping is impossible by design).
     */
    public static String requireHiveName(String value, String what) {
        if (value == null || !HIVE_NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Hive " + what + ": " + value);
        }
        return value;
    }

    /**
     * Validate a raw DEFAULT literal for numeric-ish columns (positions where quoting would change
     * semantics). Accepts decimal/scientific numbers, hex literals, TRUE/FALSE.
     */
    public static String requireNumericDefault(String value) {
        if (value == null || !NUMERIC_DEFAULT_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("Invalid Hive default value: " + value);
        }
        return value;
    }

    /**
     * Validate a Hive type expression, including nested ARRAY, MAP, STRUCT, and UNIONTYPE forms.
     */
    public static String requireColumnTypeExpression(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("column type", value);
        }
        String expression = value.trim();
        scanTypeExpression(expression, "column type", false);
        return expression;
    }

    /**
     * Validate the raw table partition tail accepted by the legacy builder.
     */
    public static String requirePartitionClause(String value) {
        String clause = StringUtils.trimToEmpty(value);
        String prefix = "PARTITIONED BY";
        if (!StringUtils.startsWithIgnoreCase(clause, prefix)) {
            throw invalid("partition clause", value);
        }
        String declaration = clause.substring(prefix.length()).trim();
        if (declaration.length() < 2 || declaration.charAt(0) != '('
                || declaration.charAt(declaration.length() - 1) != ')') {
            throw invalid("partition clause", value);
        }
        String columns = declaration.substring(1, declaration.length() - 1).trim();
        if (columns.isEmpty()) {
            throw invalid("partition clause", value);
        }
        scanTypeExpression(declaration, "partition clause", true);
        return prefix + " (" + columns + ")";
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
        throw new IllegalArgumentException("Invalid Hive index sort direction: " + value);
    }

    private static void scanTypeExpression(String expression, String description, boolean requireRootParentheses) {
        Deque<Character> delimiters = new ArrayDeque<>();
        List<String> rootWords = new ArrayList<>();
        boolean sawName = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '`') {
                i = scanBacktickQuoted(expression, i, description);
                sawName = true;
                continue;
            }
            if (startsWith(expression, i, "--") || startsWith(expression, i, "/*")
                    || startsWith(expression, i, "*/") || c == ';' || c == '\n' || c == '\r'
                    || Character.isISOControl(c)) {
                throw invalid(description, expression);
            }
            if (Character.isLetter(c) || c == '_') {
                int tokenEnd = scanWord(expression, i);
                String token = expression.substring(i, tokenEnd).toUpperCase(Locale.ROOT);
                if (TYPE_BREAKOUT_KEYWORDS.contains(token)) {
                    throw invalid(description, expression);
                }
                if (delimiters.isEmpty()) {
                    rootWords.add(token);
                }
                sawName = true;
                i = tokenEnd - 1;
                continue;
            }
            if (Character.isDigit(c) || Character.isWhitespace(c) || c == '.' || c == '$') {
                continue;
            }
            if (c == '<' || c == '(' || c == '[') {
                delimiters.push(c);
                continue;
            }
            if (c == '>' || c == ')' || c == ']') {
                if (delimiters.isEmpty() || !matches(delimiters.pop(), c)) {
                    throw invalid(description, expression);
                }
                if (requireRootParentheses && delimiters.isEmpty() && i != expression.length() - 1) {
                    throw invalid(description, expression);
                }
                continue;
            }
            if (c == ',') {
                if (delimiters.isEmpty()) {
                    throw invalid(description, expression);
                }
                continue;
            }
            if (c == ':' && delimiters.contains('<')) {
                continue;
            }
            throw invalid(description, expression);
        }
        if (!sawName || !delimiters.isEmpty()
                || (rootWords.size() > 1 && !MULTI_WORD_TYPES.contains(String.join(" ", rootWords)))) {
            throw invalid(description, expression);
        }
    }

    private static int scanBacktickQuoted(String expression, int start, String description) {
        for (int i = start + 1; i < expression.length(); i++) {
            if (expression.charAt(i) == '`') {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == '`') {
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

    private static boolean startsWith(String value, int offset, String candidate) {
        return offset + candidate.length() <= value.length() && value.startsWith(candidate, offset);
    }

    private static boolean matches(char open, char close) {
        return open == '<' && close == '>' || open == '(' && close == ')' || open == '[' && close == ']';
    }

    private static IllegalArgumentException invalid(String description, String value) {
        return new IllegalArgumentException("Invalid Hive " + description + ": " + value);
    }
}

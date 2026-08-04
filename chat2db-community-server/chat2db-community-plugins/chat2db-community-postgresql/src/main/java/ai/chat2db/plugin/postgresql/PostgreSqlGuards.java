package ai.chat2db.plugin.postgresql;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation helpers for non-escapable SQL positions in PostgreSQL DDL/DML generation
 * (strict name tokens, raw DEFAULT expressions, bit/hex literal content, enum options).
 * Escaping itself lives in
 * {@link ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor}.
 */
public final class PostgreSqlGuards {

    private static final Pattern PG_NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern BIT_LITERAL_PATTERN = Pattern.compile("^[01]*$");
    private static final Pattern HEX_LITERAL_PATTERN = Pattern.compile("^[0-9a-fA-F]*$");
    private static final Set<String> DEFAULT_BREAKOUT_KEYWORDS = Set.of(
            "CHECK", "CONSTRAINT", "DEFAULT", "GENERATED", "PRIMARY", "REFERENCES", "UNIQUE",
            "DROP", "ALTER", "CREATE", "GRANT", "REVOKE", "TRUNCATE");
    private static final Set<String> TYPE_BREAKOUT_KEYWORDS = Set.of(
            "CHECK", "COLLATE", "CONSTRAINT", "DEFAULT", "GENERATED", "NOT", "NULL",
            "PRIMARY", "REFERENCES", "UNIQUE");
    private static final Set<String> PRIVILEGES = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "MAINTAIN");
    private static final Set<String> VIEW_STORAGE_CLAUSES = Set.of(
            "TEMP", "LOCAL TEMP", "GLOBAL TEMP", "UNLOGGED");

    private PostgreSqlGuards() {
    }

    /**
     * Validate a strict PostgreSQL name token (index method / role / keyword-style positions where
     * escaping is impossible by design).
     */
    public static String requirePgName(String value, String what) {
        if (value == null || !PG_NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid PostgreSQL " + what + ": " + value);
        }
        return value;
    }

    /**
     * Validates one quote-aware PostgreSQL DEFAULT expression. Function calls, casts, sequence
     * expressions, array subscripts, dollar-quoted strings, and nested parentheses are preserved,
     * while statement terminators, comments, unbalanced delimiters, top-level commas, and column
     * constraint suffixes are rejected.
     */
    public static String requireDefaultExpression(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("default value", value);
        }
        String expression = value.trim();
        scanDefaultExpression(expression);
        return expression;
    }

    /**
     * Validates a PostgreSQL type expression used when the type is not an exact enum match.
     * Supports qualified and quoted user-defined types, type modifiers, multi-word built-ins,
     * and array suffixes without allowing the value to append a column constraint.
     */
    public static String requireColumnTypeExpression(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("column type", value);
        }
        String expression = value.trim();
        Deque<Character> delimiters = new ArrayDeque<>();
        boolean sawName = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '"') {
                int quoteEnd = scanQuoted(expression, i, '"', false, "column type");
                sawName = true;
                i = quoteEnd;
                continue;
            }
            if (startsWith(expression, i, "--") || startsWith(expression, i, "/*")
                    || startsWith(expression, i, "*/") || c == ';' || Character.isISOControl(c)) {
                throw invalid("column type", value);
            }
            if (Character.isLetter(c) || c == '_') {
                int tokenEnd = scanWord(expression, i);
                String token = expression.substring(i, tokenEnd).toUpperCase(Locale.ROOT);
                if (TYPE_BREAKOUT_KEYWORDS.contains(token)) {
                    throw invalid("column type", value);
                }
                sawName = true;
                i = tokenEnd - 1;
                continue;
            }
            if (Character.isDigit(c) || Character.isWhitespace(c) || c == '.' || c == '$') {
                continue;
            }
            if (c == '(') {
                delimiters.push(c);
                continue;
            }
            if (c == ')') {
                if (delimiters.isEmpty() || delimiters.pop() != '(') {
                    throw invalid("column type", value);
                }
                continue;
            }
            if (c == ',') {
                if (delimiters.isEmpty()) {
                    throw invalid("column type", value);
                }
                continue;
            }
            if (c == '[' && i + 1 < expression.length() && expression.charAt(i + 1) == ']') {
                i++;
                continue;
            }
            throw invalid("column type", value);
        }
        if (!sawName || !delimiters.isEmpty()) {
            throw invalid("column type", value);
        }
        return expression;
    }

    /**
     * Returns whether a temporal default should be treated as SQL syntax instead of a text value.
     */
    public static boolean isTemporalExpression(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        String expression = value.trim();
        String upper = expression.toUpperCase(Locale.ROOT);
        return expression.indexOf('(') >= 0 || expression.contains("::")
                || upper.startsWith("CURRENT_") || upper.startsWith("CURRENT ")
                || upper.startsWith("LOCALTIME") || upper.startsWith("LOCALTIMESTAMP")
                || upper.startsWith("DATE '") || upper.startsWith("TIME '")
                || upper.startsWith("TIMESTAMP '") || upper.startsWith("INTERVAL '");
    }

    public static boolean isFunctionOrCastExpression(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        String expression = value.trim();
        int openParenthesis = expression.indexOf('(');
        return expression.contains("::") || openParenthesis > 0;
    }

    public static String requirePrivilege(String value) {
        String privilege = StringUtils.trimToEmpty(value).toUpperCase(Locale.ROOT);
        if (!PRIVILEGES.contains(privilege)) {
            throw invalid("privilege", value);
        }
        return privilege;
    }

    public static String requireViewStorageClause(String value) {
        String storageClause = StringUtils.normalizeSpace(value).toUpperCase(Locale.ROOT);
        if (!VIEW_STORAGE_CLAUSES.contains(storageClause)) {
            throw invalid("view storage clause", value);
        }
        return storageClause;
    }

    /**
     * Validate content of a B'...' bit literal.
     */
    public static String requireBitLiteral(String value) {
        if (value == null || !BIT_LITERAL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid PostgreSQL bit literal: " + value);
        }
        return value;
    }

    /**
     * Validate content of a \x... bytea hex literal.
     */
    public static String requireHexLiteral(String value) {
        if (value == null || !HEX_LITERAL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid PostgreSQL bytea hex literal: " + value);
        }
        return value;
    }

    /**
     * Validate an option that must be one of the given enum constants (e.g. view check option).
     * Returns the canonical enum name.
     */
    public static <E extends Enum<E>> String requireEnumConstant(String value, E[] constants, String what) {
        for (E constant : constants) {
            if (constant.name().equalsIgnoreCase(StringUtils.trimToEmpty(value))) {
                return constant.name();
            }
        }
        throw new IllegalArgumentException("Invalid PostgreSQL " + what + ": " + value);
    }

    private static void scanDefaultExpression(String expression) {
        Deque<Character> delimiters = new ArrayDeque<>();
        boolean sawContent = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\'' || c == '"') {
                boolean escapeBackslash = c == '\'' && i > 0 && (expression.charAt(i - 1) == 'E'
                        || expression.charAt(i - 1) == 'e')
                        && (i == 1 || !isIdentifierCharacter(expression.charAt(i - 2)));
                i = scanQuoted(expression, i, c, escapeBackslash, "default value");
                sawContent = true;
                continue;
            }
            if (c == '$') {
                int dollarEnd = scanDollarQuoted(expression, i);
                if (dollarEnd >= 0) {
                    i = dollarEnd;
                    sawContent = true;
                    continue;
                }
            }
            if (startsWith(expression, i, "--") || startsWith(expression, i, "/*")
                    || startsWith(expression, i, "*/") || c == ';' || c == '\n' || c == '\r'
                    || Character.isISOControl(c)) {
                throw invalid("default value", expression);
            }
            if (c == '(' || c == '[') {
                delimiters.push(c);
                sawContent = true;
                continue;
            }
            if (c == ')' || c == ']') {
                if (delimiters.isEmpty() || !matches(delimiters.pop(), c)) {
                    throw invalid("default value", expression);
                }
                sawContent = true;
                continue;
            }
            if (c == ',' && delimiters.isEmpty()) {
                throw invalid("default value", expression);
            }
            if (Character.isLetter(c) || c == '_') {
                int tokenEnd = scanWord(expression, i);
                if (delimiters.isEmpty()) {
                    String token = expression.substring(i, tokenEnd).toUpperCase(Locale.ROOT);
                    if (DEFAULT_BREAKOUT_KEYWORDS.contains(token)
                            || (("NOT".equals(token) || "NULL".equals(token)) && sawContent)) {
                        throw invalid("default value", expression);
                    }
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
            throw invalid("default value", expression);
        }
    }

    private static int scanQuoted(String expression, int quoteStart, char quote,
                                  boolean escapeBackslash, String description) {
        for (int i = quoteStart + 1; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (escapeBackslash && c == '\\') {
                if (++i >= expression.length()) {
                    throw invalid(description, expression);
                }
                continue;
            }
            if (c == quote) {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == quote) {
                    i++;
                } else {
                    return i;
                }
            }
        }
        throw invalid(description, expression);
    }

    private static int scanDollarQuoted(String expression, int start) {
        int delimiterEnd = expression.indexOf('$', start + 1);
        if (delimiterEnd < 0) {
            return -1;
        }
        for (int i = start + 1; i < delimiterEnd; i++) {
            if (!isIdentifierCharacter(expression.charAt(i))) {
                return -1;
            }
        }
        String delimiter = expression.substring(start, delimiterEnd + 1);
        int contentEnd = expression.indexOf(delimiter, delimiterEnd + 1);
        if (contentEnd < 0) {
            throw invalid("default value", expression);
        }
        return contentEnd + delimiter.length() - 1;
    }

    private static int scanWord(String expression, int start) {
        int current = start + 1;
        while (current < expression.length() && isIdentifierCharacter(expression.charAt(current))) {
            current++;
        }
        return current;
    }

    private static boolean isIdentifierCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean startsWith(String value, int offset, String candidate) {
        return offset + candidate.length() <= value.length() && value.startsWith(candidate, offset);
    }

    private static boolean matches(char open, char close) {
        return open == '(' && close == ')' || open == '[' && close == ']';
    }

    private static IllegalArgumentException invalid(String description, String value) {
        return new IllegalArgumentException("Invalid PostgreSQL " + description + ": " + value);
    }
}

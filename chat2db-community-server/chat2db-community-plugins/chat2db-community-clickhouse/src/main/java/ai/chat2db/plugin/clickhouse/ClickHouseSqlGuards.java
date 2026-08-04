package ai.chat2db.plugin.clickhouse;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Structural validation for ClickHouse expressions that cannot be escaped
 * because they are emitted as SQL syntax.
 */
public final class ClickHouseSqlGuards {

    private ClickHouseSqlGuards() {
    }

    /**
     * Accepts one ClickHouse column type expression, including nested types,
     * enum literals, time zones, and quoted tuple field names.
     */
    public static String requireColumnTypeExpression(String columnType) {
        if (StringUtils.isBlank(columnType)) {
            throw invalid("column type", columnType);
        }
        String expression = columnType.trim();
        int nameEnd = scanIdentifier(expression, 0);
        if (nameEnd == 0) {
            throw invalid("column type", columnType);
        }
        int argumentsStart = skipWhitespace(expression, nameEnd);
        if (argumentsStart == expression.length()) {
            return expression;
        }
        if (expression.charAt(argumentsStart) != '(') {
            throw invalid("column type", columnType);
        }
        int argumentsEnd = scanExpression(expression, argumentsStart, true, true, false, "column type");
        if (skipWhitespace(expression, argumentsEnd + 1) != expression.length()) {
            throw invalid("column type", columnType);
        }
        return expression;
    }

    /**
     * Accepts one engine name with an optional, fully balanced argument list.
     */
    public static String requireEngine(String engine) {
        if (StringUtils.isBlank(engine)) {
            throw invalid("engine", engine);
        }
        String expression = engine.trim();
        int nameEnd = scanIdentifier(expression, 0);
        if (nameEnd == 0) {
            throw invalid("engine", engine);
        }
        int argumentsStart = skipWhitespace(expression, nameEnd);
        if (argumentsStart == expression.length()) {
            return expression;
        }
        if (expression.charAt(argumentsStart) != '(') {
            throw invalid("engine", engine);
        }
        int argumentsEnd = scanExpression(expression, argumentsStart, true, false, false, "engine");
        if (skipWhitespace(expression, argumentsEnd + 1) != expression.length()) {
            throw invalid("engine", engine);
        }
        return expression;
    }

    /**
     * Validates one default expression without re-encoding serialized SQL
     * literals returned by {@code system.columns.default_expression}.
     */
    public static String escapeDefaultExpression(String defaultValue) {
        if (StringUtils.isBlank(defaultValue)) {
            throw invalid("default expression", defaultValue);
        }
        String expression = defaultValue.trim();
        scanExpression(expression, 0, false, false, true, "default expression");
        return expression;
    }

    private static int scanIdentifier(String expression, int offset) {
        if (offset >= expression.length()
                || !(Character.isLetter(expression.charAt(offset)) || expression.charAt(offset) == '_')) {
            return offset;
        }
        int current = offset + 1;
        while (current < expression.length()) {
            char c = expression.charAt(current);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                break;
            }
            current++;
        }
        return current;
    }

    private static int skipWhitespace(String expression, int offset) {
        int current = offset;
        while (current < expression.length() && Character.isWhitespace(expression.charAt(current))) {
            current++;
        }
        return current;
    }

    /**
     * Scans a quote-aware expression. When {@code stopAtRootClose} is true,
     * scanning starts on an opening parenthesis and returns its matching close.
     */
    private static int scanExpression(String expression, int start, boolean stopAtRootClose,
                                      boolean typeCharactersOnly, boolean rejectTopLevelComma,
                                      String description) {
        Deque<Character> delimiters = new ArrayDeque<>();
        char quote = 0;
        for (int i = start; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    if (i + 1 >= expression.length()) {
                        throw invalid(description, expression);
                    }
                    i++;
                    continue;
                }
                if (c == quote) {
                    if (i + 1 < expression.length() && expression.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }

            if (c == '\'' || c == '`' || c == '"') {
                quote = c;
                continue;
            }
            if (c == ';' || c == '#' || c == '\n' || c == '\r'
                    || startsWith(expression, i, "--")
                    || startsWith(expression, i, "/*")
                    || startsWith(expression, i, "*/")) {
                throw invalid(description, expression);
            }
            if (c == '(' || c == '[' || c == '{') {
                delimiters.push(c);
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                if (delimiters.isEmpty() || !matches(delimiters.pop(), c)) {
                    throw invalid(description, expression);
                }
                if (stopAtRootClose && delimiters.isEmpty()) {
                    return i;
                }
                continue;
            }
            if (rejectTopLevelComma && c == ',' && delimiters.isEmpty()) {
                throw invalid(description, expression);
            }
            if (typeCharactersOnly && !isTypeCharacter(c)) {
                throw invalid(description, expression);
            }
            if (Character.isISOControl(c)) {
                throw invalid(description, expression);
            }
        }
        if (quote != 0 || !delimiters.isEmpty() || stopAtRootClose) {
            throw invalid(description, expression);
        }
        return expression.length();
    }

    private static boolean isTypeCharacter(char c) {
        return Character.isLetterOrDigit(c) || Character.isWhitespace(c)
                || c == '_' || c == ',' || c == '=' || c == '+' || c == '-'
                || c == '.';
    }

    private static boolean startsWith(String value, int offset, String candidate) {
        return offset + candidate.length() <= value.length()
                && value.startsWith(candidate, offset);
    }

    private static boolean matches(char open, char close) {
        return open == '(' && close == ')' || open == '[' && close == ']' || open == '{' && close == '}';
    }

    private static IllegalArgumentException invalid(String description, String value) {
        return new IllegalArgumentException("Invalid ClickHouse " + description + ": " + value);
    }
}

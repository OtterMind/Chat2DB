package ai.chat2db.plugin.mysql;

import ai.chat2db.mysql.parser.base.MySqlLexer;
import ai.chat2db.mysql.parser.base.MySqlParser;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validation helpers for non-escapable SQL positions in MySQL DDL/DML generation
 * (engine/charset/collation names, raw numeric defaults, bit/hex literals, definers,
 * index sort directions and fixed option sets). Escaping itself lives in
 * {@link MysqlIdentifierProcessor}.
 */
public final class MysqlSqlGuards {

    private static final Pattern MYSQL_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final Pattern NUMERIC_DEFAULT_PATTERN = Pattern.compile(
            "^([+-]?(\\d+(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?|0[xX][0-9a-fA-F]+|[xX]'[0-9a-fA-F]*'|[bB]'[01]*'|(?i:TRUE|FALSE))$");
    private static final Pattern BIT_LITERAL_PATTERN = Pattern.compile("^[01]+$");
    private static final Pattern HEX_DIGITS_PATTERN = Pattern.compile("^[0-9a-fA-F]+$");
    private static final Pattern HEX_LITERAL_PATTERN = Pattern.compile("^0[xX][0-9a-fA-F]+$");
    private static final String DEFINER_SINGLE_QUOTED_PART = "'(?:''|[^'\\\\])+'";
    private static final String DEFINER_BACKTICK_QUOTED_PART = "`(?:``|[^`\\\\])+`";
    private static final String DEFINER_QUOTED_PART = "(?:" + DEFINER_SINGLE_QUOTED_PART + "|"
            + DEFINER_BACKTICK_QUOTED_PART + ")";
    private static final Pattern DEFINER_PATTERN = Pattern.compile(
            "^([A-Za-z0-9_$]+|" + DEFINER_QUOTED_PART + ")@([A-Za-z0-9_.%:$-]+|" + DEFINER_QUOTED_PART + ")$");
    private static final Pattern COLUMN_TYPE_PATTERN = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*\\))?(?:\\s+[A-Za-z][A-Za-z0-9_]*)*$");
    private static final Pattern FUNCTIONAL_INDEX_UNSAFE_TOKEN_PATTERN = Pattern.compile("(;|--|#|/\\*|\\*/)");
    private static final Pattern BARE_IDENTIFIER_PATTERN = Pattern.compile("^`(?:``|[^`])+`$|^[A-Za-z_$][A-Za-z0-9_$]*$");
    private static final Pattern NONDETERMINISTIC_FUNCTION_PATTERN = Pattern.compile(
            "(^|[^A-Za-z0-9_$])(?:connection_id|current_date|current_time|current_timestamp|curdate|curtime|database|last_insert_id|localtime|localtimestamp|now|rand|sysdate|uuid|uuid_short|version)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MYSQL_VERSION_PATTERN = Pattern.compile(".*?(\\d+)\\.(\\d+)\\.(\\d+).*");
    private static final BaseErrorListener THROWING_ERROR_LISTENER = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String message, RecognitionException exception) {
            throw new ParseCancellationException(message, exception);
        }
    };

    private MysqlSqlGuards() {
    }

    /**
     * Validate a strict MySQL name token (ENGINE / CHARACTER SET / COLLATE style positions where
     * escaping is impossible by design).
     */
    public static String requireMysqlName(String value, String what) {
        if (value == null || !MYSQL_NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid MySQL " + what + ": " + value);
        }
        return value;
    }

    /**
     * Validate a raw DEFAULT literal for numeric-ish columns (positions where quoting would change
     * semantics). Accepts decimal/scientific numbers, hex and bit literals, TRUE/FALSE.
     */
    public static String requireNumericDefault(String value) {
        if (value == null || !NUMERIC_DEFAULT_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("Invalid MySQL default value: " + value);
        }
        return value;
    }

    /**
     * Validate content of a b'...' bit literal.
     */
    public static String requireBitLiteral(String value) {
        if (value == null || !BIT_LITERAL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid MySQL bit literal: " + value);
        }
        return value;
    }

    /**
     * Validate the digits of a 0x... hex literal (the template adds the 0x prefix).
     */
    public static String requireHexDigits(String value) {
        if (value == null || !HEX_DIGITS_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid MySQL hex digits: " + value);
        }
        return value;
    }

    /**
     * True only when the value is a well-formed 0x... hex literal. Values that merely
     * start with 0x but contain non-hex characters must not pass through into SQL raw.
     */
    public static boolean isHexLiteral(String value) {
        return value != null && HEX_LITERAL_PATTERN.matcher(value).matches();
    }

    /**
     * Validate a DEFINER value (user@host, parts optionally single-quoted or backtick-quoted).
     */
    public static String requireDefiner(String value) {
        if (value == null || !DEFINER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid MySQL definer: " + value);
        }
        return value;
    }

    /**
     * Validate a fallback column type expression while preserving common custom type names,
     * optional numeric precision/scale and keyword modifiers.
     */
    public static String requireColumnType(String value) {
        String trimmed = StringUtils.trimToEmpty(value);
        if (!COLUMN_TYPE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid MySQL column type: " + value);
        }
        return trimmed;
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
        throw new IllegalArgumentException("Invalid MySQL index sort direction: " + value);
    }

    public static String requireFunctionalIndexExpression(String value) {
        String expression = normalizeFunctionalIndexExpression(value);
        if (!isFunctionalIndexExpressionSyntaxSupported(expression)) {
            throw new IllegalArgumentException("Invalid MySQL functional index expression: " + value);
        }
        return expression;
    }

    public static boolean isFunctionalIndexExpressionSyntaxSupported(String value) {
        String expression = normalizeFunctionalIndexExpression(value);
        if (StringUtils.isBlank(expression)
                || BARE_IDENTIFIER_PATTERN.matcher(expression).matches()
                || FUNCTIONAL_INDEX_UNSAFE_TOKEN_PATTERN.matcher(expression).find()
                || NONDETERMINISTIC_FUNCTION_PATTERN.matcher(expression).find()
                || !hasBalancedExpressionDelimiters(expression)) {
            return false;
        }
        try {
            MySqlLexer lexer = new MySqlLexer(CharStreams.fromString(expression));
            lexer.removeErrorListeners();
            lexer.addErrorListener(THROWING_ERROR_LISTENER);
            CommonTokenStream tokenStream = new CommonTokenStream(lexer);
            MySqlParser parser = new MySqlParser(tokenStream);
            parser.removeErrorListeners();
            parser.addErrorListener(THROWING_ERROR_LISTENER);
            parser.setErrorHandler(new BailErrorStrategy());
            parser.expression();
            return tokenStream.LA(1) == Token.EOF;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static String normalizeFunctionalIndexExpression(String value) {
        String expression = StringUtils.trimToEmpty(value);
        while (hasOuterParentheses(expression)) {
            expression = expression.substring(1, expression.length() - 1).trim();
        }
        return expression;
    }

    public static boolean supportsFunctionalIndex(String version) {
        java.util.regex.Matcher matcher = MYSQL_VERSION_PATTERN.matcher(StringUtils.trimToEmpty(version));
        if (!matcher.matches()) {
            return false;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));
        return major > 8 || (major == 8 && (minor > 0 || patch >= 13));
    }

    /**
     * Validate an option that must be one of the given enum constants (e.g. view algorithm /
     * sql security / check option). Returns the canonical enum name.
     */
    public static <E extends Enum<E>> String requireEnumConstant(String value, E[] constants, String what) {
        for (E constant : constants) {
            if (constant.name().equalsIgnoreCase(StringUtils.trimToEmpty(value))) {
                return constant.name();
            }
        }
        throw new IllegalArgumentException("Invalid MySQL " + what + ": " + value);
    }

    /**
     * Parse and re-escape a comma-separated ENUM/SET value list. Quoted values are decoded before
     * they are escaped again, so metadata such as {@code 'can''t'} is not double-escaped. A
     * surrounding pair of parentheses is preserved.
     */
    public static String quoteEnumValues(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        boolean parenthesized = trimmed.startsWith("(");
        List<String> items = parseEnumValues(trimmed);
        StringBuilder result = new StringBuilder();
        if (parenthesized) {
            result.append('(');
        }
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append('\'').append(MysqlIdentifierProcessor.INSTANCE.escapeString(items.get(i))).append('\'');
        }
        if (parenthesized) {
            result.append(')');
        }
        return result.toString();
    }

    /**
     * Parses a MySQL ENUM/SET value list while honoring quoted commas, doubled quotes and
     * backslash escapes. The input may optionally include its surrounding parentheses.
     */
    public static List<String> parseEnumValues(String raw) {
        if (raw == null) {
            throw invalidEnumValues(null);
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw invalidEnumValues(raw);
        }
        boolean startsWithParenthesis = trimmed.startsWith("(");
        boolean endsWithParenthesis = trimmed.endsWith(")");
        if (startsWithParenthesis != endsWithParenthesis) {
            throw invalidEnumValues(raw);
        }
        String inner = startsWithParenthesis ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
        return List.copyOf(parseEnumValues(inner, raw));
    }

    private static List<String> parseEnumValues(String inner, String raw) {
        List<String> values = new ArrayList<>();
        int index = 0;
        while (index < inner.length()) {
            while (index < inner.length() && Character.isWhitespace(inner.charAt(index))) {
                index++;
            }
            if (index >= inner.length()) {
                throw invalidEnumValues(raw);
            }

            String value;
            if (inner.charAt(index) == '\'') {
                StringBuilder decoded = new StringBuilder();
                index = parseQuotedEnumValue(inner, index + 1, decoded, raw);
                value = decoded.toString();
                while (index < inner.length() && Character.isWhitespace(inner.charAt(index))) {
                    index++;
                }
                if (index < inner.length() && inner.charAt(index) != ',') {
                    throw invalidEnumValues(raw);
                }
            } else {
                int comma = inner.indexOf(',', index);
                int end = comma < 0 ? inner.length() : comma;
                value = inner.substring(index, end).trim();
                if (value.isEmpty()) {
                    throw invalidEnumValues(raw);
                }
                index = end;
            }
            values.add(value);

            if (index >= inner.length()) {
                break;
            }
            index++;
            if (index >= inner.length()) {
                throw invalidEnumValues(raw);
            }
        }
        if (values.isEmpty()) {
            throw invalidEnumValues(raw);
        }
        return values;
    }

    private static int parseQuotedEnumValue(String inner, int index, StringBuilder decoded, String raw) {
        while (index < inner.length()) {
            char current = inner.charAt(index++);
            if (current == '\'') {
                if (index < inner.length() && inner.charAt(index) == '\'') {
                    decoded.append('\'');
                    index++;
                    continue;
                }
                return index;
            }
            if (current == '\\') {
                if (index >= inner.length()) {
                    throw invalidEnumValues(raw);
                }
                appendMysqlEscapedCharacter(decoded, inner.charAt(index++));
                continue;
            }
            decoded.append(current);
        }
        throw invalidEnumValues(raw);
    }

    private static void appendMysqlEscapedCharacter(StringBuilder decoded, char escaped) {
        switch (escaped) {
            case '0' -> decoded.append('\0');
            case 'b' -> decoded.append('\b');
            case 'n' -> decoded.append('\n');
            case 'r' -> decoded.append('\r');
            case 't' -> decoded.append('\t');
            case 'Z' -> decoded.append((char) 26);
            case '%', '_' -> decoded.append('\\').append(escaped);
            default -> decoded.append(escaped);
        }
    }

    private static IllegalArgumentException invalidEnumValues(String raw) {
        return new IllegalArgumentException("Invalid MySQL ENUM/SET values: " + raw);
    }

    private static boolean hasOuterParentheses(String expression) {
        if (expression.length() < 2 || expression.charAt(0) != '(' || expression.charAt(expression.length() - 1) != ')') {
            return false;
        }
        int depth = 0;
        for (int index = 0; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0 && index < expression.length() - 1) {
                    return false;
                }
            }
            if (depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    private static boolean hasBalancedExpressionDelimiters(String expression) {
        int depth = 0;
        Character quote = null;
        for (int index = 0; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (quote != null) {
                if (character == '\\') {
                    index++;
                    continue;
                }
                if (character == quote) {
                    if ((quote == '`' || quote == '\'') && index + 1 < expression.length()
                            && expression.charAt(index + 1) == quote) {
                        index++;
                    } else {
                        quote = null;
                    }
                }
                continue;
            }
            if (character == '\'' || character == '"' || character == '`') {
                quote = character;
            } else if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0 && quote == null;
    }
}

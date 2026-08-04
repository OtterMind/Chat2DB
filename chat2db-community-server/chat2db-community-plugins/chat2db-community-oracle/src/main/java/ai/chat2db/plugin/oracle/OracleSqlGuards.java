package ai.chat2db.plugin.oracle;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Structural validation for Oracle SQL fragments that cannot be escaped
 * because they are emitted as syntax rather than as identifiers or literals.
 */
public final class OracleSqlGuards {

    private static final Set<String> COLUMN_CLAUSE_KEYWORDS = Set.of(
            "COLLATE", "CONSTRAINT", "CHECK", "DEFAULT", "DISABLE", "ENABLE", "GENERATED",
            "IDENTITY", "INVISIBLE", "PRIMARY", "REFERENCES", "UNIQUE", "VISIBLE");

    private OracleSqlGuards() {
    }

    /**
     * Validates one Oracle DEFAULT expression without re-encoding literals
     * returned by the data dictionary. Functions, casts, sequence expressions,
     * datetime/interval literals, quoted identifiers, and Oracle q-quotes are
     * accepted when their delimiters are balanced.
     */
    public static String requireDefaultValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("DEFAULT expression", value);
        }
        scanExpression(value.trim(), false, "DEFAULT expression");
        return value;
    }

    /**
     * Validates one complete Oracle column type expression, including
     * parameterized built-in types and schema-qualified user-defined types.
     */
    public static String requireColumnTypeExpression(String typeName) {
        if (StringUtils.isBlank(typeName)) {
            throw invalid("column type", typeName);
        }
        scanExpression(typeName.trim(), true, "column type");
        return typeName;
    }

    /**
     * Backward-compatible name retained for the existing Oracle call sites.
     */
    public static String requireSafeTypeName(String typeName) {
        return requireColumnTypeExpression(typeName);
    }

    public static String requireUnit(String unit) {
        String trimmed = StringUtils.trimToEmpty(unit);
        if (!"CHAR".equalsIgnoreCase(trimmed) && !"BYTE".equalsIgnoreCase(trimmed)) {
            throw new IllegalArgumentException("Unsupported Oracle VARCHAR unit: " + unit);
        }
        return trimmed;
    }

    public static String requireAscOrDesc(String value) {
        String trimmed = StringUtils.trimToEmpty(value);
        if ("ASC".equalsIgnoreCase(trimmed)) {
            return "ASC";
        }
        if ("DESC".equalsIgnoreCase(trimmed)) {
            return "DESC";
        }
        throw new IllegalArgumentException("Invalid Oracle index sort direction: " + value);
    }

    /**
     * Returns the raw hex digits represented by {@code value}. Non-hex input is
     * replaced with the caller-provided base16 encoding, which is verified too.
     */
    public static String normalizeHexLiteral(String value, String fallbackHex) {
        if (value == null) {
            return null;
        }
        String candidate = value.startsWith("0x") ? value.substring(2) : value;
        if (isHex(candidate)) {
            return candidate;
        }
        if (fallbackHex != null && isHex(fallbackHex)) {
            return fallbackHex;
        }
        throw new IllegalArgumentException("Invalid Oracle RAW/BLOB hex value");
    }

    public static String requireKeyword(String description, String value, String... allowedValues) {
        for (String allowedValue : allowedValues) {
            if (allowedValue.equalsIgnoreCase(StringUtils.trimToEmpty(value))) {
                return allowedValue;
            }
        }
        throw new IllegalArgumentException("Unsupported Oracle " + description + ": " + value);
    }

    private static void scanExpression(String expression, boolean typeExpression, String description) {
        Deque<Character> delimiters = new ArrayDeque<>();
        List<String> topLevelWords = new ArrayList<>();
        boolean sawToken = false;

        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            sawToken = true;

            if (isAlternativeQuoteStart(expression, i)) {
                if (typeExpression) {
                    throw invalid(description, expression);
                }
                i = scanAlternativeQuote(expression, i, description);
                continue;
            }
            if (c == '\'' || c == '"') {
                if (typeExpression && c == '\'') {
                    throw invalid(description, expression);
                }
                i = scanQuoted(expression, i, c, description);
                continue;
            }
            if (c == ';' || Character.isISOControl(c)
                    || startsWith(expression, i, "--")
                    || startsWith(expression, i, "/*")
                    || startsWith(expression, i, "*/")) {
                throw invalid(description, expression);
            }
            if (c == '(') {
                delimiters.push(c);
                continue;
            }
            if (c == ')') {
                if (delimiters.isEmpty()) {
                    throw invalid(description, expression);
                }
                delimiters.pop();
                continue;
            }
            if (c == '[' || c == ']' || c == '{' || c == '}') {
                throw invalid(description, expression);
            }
            if (c == ',' && delimiters.isEmpty()) {
                throw invalid(description, expression);
            }
            if (typeExpression && !isTypeCharacter(c)) {
                throw invalid(description, expression);
            }
            if (Character.isLetter(c) || c == '_') {
                int wordEnd = i + 1;
                while (wordEnd < expression.length() && isWordCharacter(expression.charAt(wordEnd))) {
                    wordEnd++;
                }
                if (delimiters.isEmpty()) {
                    topLevelWords.add(expression.substring(i, wordEnd).toUpperCase(Locale.ROOT));
                }
                i = wordEnd - 1;
            }
        }

        if (!sawToken || !delimiters.isEmpty()) {
            throw invalid(description, expression);
        }
        rejectColumnClauseTokens(topLevelWords, description, expression);
    }

    private static int scanQuoted(String expression, int start, char quote, String description) {
        for (int i = start + 1; i < expression.length(); i++) {
            if (expression.charAt(i) == quote) {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == quote) {
                    i++;
                    continue;
                }
                return i;
            }
        }
        throw invalid(description, expression);
    }

    private static boolean isAlternativeQuoteStart(String expression, int offset) {
        return offset + 2 < expression.length()
                && (expression.charAt(offset) == 'q' || expression.charAt(offset) == 'Q')
                && expression.charAt(offset + 1) == '\'';
    }

    private static int scanAlternativeQuote(String expression, int start, String description) {
        char open = expression.charAt(start + 2);
        char close = switch (open) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> open;
        };
        for (int i = start + 3; i + 1 < expression.length(); i++) {
            if (expression.charAt(i) == close && expression.charAt(i + 1) == '\'') {
                return i + 1;
            }
        }
        throw invalid(description, expression);
    }

    private static void rejectColumnClauseTokens(List<String> words, String description, String expression) {
        for (String word : words) {
            if (COLUMN_CLAUSE_KEYWORDS.contains(word)) {
                throw invalid(description, expression);
            }
        }
        for (int i = 0; i + 1 < words.size(); i++) {
            if ("NOT".equals(words.get(i)) && "NULL".equals(words.get(i + 1))) {
                throw invalid(description, expression);
            }
        }
    }

    private static boolean isTypeCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#'
                || c == '.' || c == '%' || c == '*' || c == '+' || c == '-' || c == ',';
    }

    private static boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'A' || c > 'F') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(String value, int offset, String candidate) {
        return offset + candidate.length() <= value.length() && value.startsWith(candidate, offset);
    }

    private static IllegalArgumentException invalid(String description, String value) {
        return new IllegalArgumentException("Invalid Oracle " + description + ": " + value);
    }
}

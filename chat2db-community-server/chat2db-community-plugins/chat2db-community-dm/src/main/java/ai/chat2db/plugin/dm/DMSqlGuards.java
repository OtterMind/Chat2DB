package ai.chat2db.plugin.dm;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Structural validation for DM SQL fragments that are emitted as syntax
 * rather than as identifiers or string literals.
 */
public final class DMSqlGuards {

    private static final Set<String> COLUMN_CLAUSE_KEYWORDS = Set.of(
            "COLLATE", "CONSTRAINT", "CHECK", "DEFAULT", "DISABLE", "ENABLE", "GENERATED",
            "IDENTITY", "INVISIBLE", "PRIMARY", "REFERENCES", "UNIQUE", "VISIBLE");

    private DMSqlGuards() {
    }

    /**
     * Validates one DM DEFAULT expression without re-encoding serialized
     * literals returned by metadata.
     */
    public static String requireDefaultExpression(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("DEFAULT expression", value);
        }
        scanExpression(value.trim(), false, "DEFAULT expression");
        return value;
    }

    /**
     * Validates one complete DM column type expression, including
     * parameterized built-in and schema-qualified user-defined types.
     */
    public static String requireColumnTypeExpression(String typeName) {
        if (StringUtils.isBlank(typeName)) {
            throw invalid("column type", typeName);
        }
        scanExpression(typeName.trim(), true, "column type");
        return typeName;
    }

    public static String requireUnit(String unit) {
        String trimmed = StringUtils.trimToEmpty(unit);
        if (!"CHAR".equalsIgnoreCase(trimmed) && !"BYTE".equalsIgnoreCase(trimmed)) {
            throw new IllegalArgumentException("Unsupported DM VARCHAR unit: " + unit);
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
        throw new IllegalArgumentException("Invalid DM index sort direction: " + value);
    }

    public static String requireBitLiteral(String value) {
        if (StringUtils.isBlank(value)) {
            return "NULL";
        }
        String trimmed = StringUtils.trimToEmpty(value);
        if ("0".equals(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return "0";
        }
        if ("1".equals(trimmed) || "true".equalsIgnoreCase(trimmed)) {
            return "1";
        }
        throw new IllegalArgumentException("Invalid DM BIT literal: " + value);
    }

    private static void scanExpression(String expression, boolean typeExpression, String description) {
        Deque<Character> delimiters = new ArrayDeque<>();
        List<String> topLevelWords = new ArrayList<>();
        boolean sawToken = false;

        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (Character.isISOControl(c)) {
                throw invalid(description, expression);
            }
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
                int end = scanQuoted(expression, i, c, description);
                if (c == '\'' && hasInvalidAttachedLiteralPrefix(expression, i, end)) {
                    throw invalid(description, expression);
                }
                i = end;
                continue;
            }
            if (c == ';'
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

    private static boolean hasInvalidAttachedLiteralPrefix(String expression, int quoteStart, int quoteEnd) {
        if (quoteStart == 0 || Character.isWhitespace(expression.charAt(quoteStart - 1))) {
            return false;
        }
        if (!isWordCharacter(expression.charAt(quoteStart - 1))) {
            return false;
        }
        int prefixStart = quoteStart - 1;
        while (prefixStart > 0 && isWordCharacter(expression.charAt(prefixStart - 1))) {
            prefixStart--;
        }
        String prefix = expression.substring(prefixStart, quoteStart);
        if ("N".equalsIgnoreCase(prefix)) {
            return false;
        }
        if (!"X".equalsIgnoreCase(prefix)) {
            return true;
        }
        for (int i = quoteStart + 1; i < quoteEnd; i++) {
            char c = expression.charAt(i);
            if ((c < '0' || c > '9') && (c < 'A' || c > 'F') && (c < 'a' || c > 'f')) {
                return true;
            }
        }
        return false;
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

    private static boolean startsWith(String value, int offset, String candidate) {
        return offset + candidate.length() <= value.length() && value.startsWith(candidate, offset);
    }

    private static IllegalArgumentException invalid(String description, String value) {
        return new IllegalArgumentException("Invalid DM " + description + ": " + value);
    }
}

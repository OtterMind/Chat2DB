package ai.chat2db.plugin.xugudb;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Structural validation for XuguDB SQL fragments emitted as syntax rather
 * than identifiers or string literals.
 */
public final class XugudbSqlGuards {

    private static final Set<String> COLUMN_CLAUSE_KEYWORDS = Set.of(
            "CHECK", "COLLATE", "CONSTRAINT", "DEFAULT", "GENERATED", "IDENTITY",
            "PRIMARY", "REFERENCES", "UNIQUE");

    private static final Set<String> STATEMENT_KEYWORDS = Set.of(
            "ALTER", "CREATE", "DELETE", "DROP", "GRANT", "INSERT", "MERGE",
            "REVOKE", "SELECT", "TRUNCATE", "UPDATE");

    private XugudbSqlGuards() {
    }

    /**
     * Validates one DEFAULT expression while preserving quoted literal content.
     * Nested calls, casts, sequence expressions, qualified names, and outer
     * parentheses are accepted when all delimiters are balanced.
     */
    public static String requireDefaultValue(String defaultValue) {
        String trimmed = StringUtils.trimToNull(defaultValue);
        if (trimmed == null) {
            throw invalid("DEFAULT expression", defaultValue);
        }
        scanExpression(trimmed, false, "DEFAULT expression");
        return trimmed;
    }

    /**
     * Validates a complete XuguDB column type expression, including
     * parameterized and schema-qualified user-defined types.
     */
    public static String requireColumnTypeExpression(String columnType) {
        String trimmed = StringUtils.trimToNull(columnType);
        if (trimmed == null) {
            throw invalid("column type", columnType);
        }
        scanExpression(trimmed, true, "column type");
        return trimmed;
    }

    public static String requireUnit(String unit) {
        String trimmed = StringUtils.trimToEmpty(unit);
        if ("BYTE".equalsIgnoreCase(trimmed)) {
            return "BYTE";
        }
        if ("CHAR".equalsIgnoreCase(trimmed)) {
            return "CHAR";
        }
        throw new IllegalArgumentException("Unsupported XuguDB length unit: " + unit);
    }

    private static void scanExpression(String expression, boolean typeExpression, String description) {
        Deque<Character> parentheses = new ArrayDeque<>();
        List<String> topLevelWords = new ArrayList<>();
        boolean sawToken = false;

        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            sawToken = true;

            if (!typeExpression && isAlternativeQuoteStart(expression, i)) {
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
                parentheses.push(c);
                continue;
            }
            if (c == ')') {
                if (parentheses.isEmpty()) {
                    throw invalid(description, expression);
                }
                parentheses.pop();
                continue;
            }
            if (c == '[' || c == ']' || c == '{' || c == '}') {
                throw invalid(description, expression);
            }
            if (c == ',' && parentheses.isEmpty()) {
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
                String word = expression.substring(i, wordEnd).toUpperCase(Locale.ROOT);
                if (STATEMENT_KEYWORDS.contains(word)) {
                    throw invalid(description, expression);
                }
                if (parentheses.isEmpty()) {
                    topLevelWords.add(word);
                }
                i = wordEnd - 1;
            }
        }

        if (!sawToken || !parentheses.isEmpty()) {
            throw invalid(description, expression);
        }
        for (String word : topLevelWords) {
            if (COLUMN_CLAUSE_KEYWORDS.contains(word)) {
                throw invalid(description, expression);
            }
        }
        for (int i = 0; i + 1 < topLevelWords.size(); i++) {
            if ("NOT".equals(topLevelWords.get(i)) && "NULL".equals(topLevelWords.get(i + 1))) {
                throw invalid(description, expression);
            }
        }
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
        return new IllegalArgumentException("Invalid XuguDB " + description + ": " + value);
    }
}

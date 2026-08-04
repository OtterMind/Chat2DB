package ai.chat2db.plugin.sundb;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validation helpers for non-escapable SQL positions in SUNDB DDL generation
 * (index sort direction, column default expressions, VARCHAR units).
 * Escaping itself lives in {@link ai.chat2db.plugin.sundb.identifier.SUNDBIdentifierProcessor}.
 */
public final class SUNDBSqlGuards {

    private static final Set<String> COLUMN_CLAUSE_KEYWORDS = Set.of(
            "AUTO_INCREMENT", "CHECK", "COLLATE", "COMMENT", "CONSTRAINT", "DEFAULT", "GENERATED", "IDENTITY",
            "PRIMARY", "REFERENCES", "UNIQUE");

    private static final Set<String> STATEMENT_KEYWORDS = Set.of(
            "ALTER", "CREATE", "DELETE", "DROP", "GRANT", "INSERT", "MERGE",
            "REVOKE", "SELECT", "TRUNCATE", "UPDATE");

    private static final Set<String> CONSTRAINT_TYPES = Set.of(
            "CHECK", "FOREIGN KEY", "PRIMARY KEY", "UNIQUE");

    private static final Set<List<String>> INTERVAL_QUALIFIERS = Set.of(
            List.of("YEAR"), List.of("MONTH"), List.of("DAY"), List.of("HOUR"),
            List.of("MINUTE"), List.of("SECOND"), List.of("YEAR", "TO", "MONTH"),
            List.of("DAY", "TO", "HOUR"), List.of("DAY", "TO", "MINUTE"),
            List.of("DAY", "TO", "SECOND"), List.of("HOUR", "TO", "MINUTE"),
            List.of("HOUR", "TO", "SECOND"), List.of("MINUTE", "TO", "SECOND"));

    private SUNDBSqlGuards() {
    }

    /**
     * Validates an index sort direction: only ASC/DESC are legal, returned in
     * canonical uppercase. Anything else is rejected to block DDL injection.
     */
    public static String requireAscOrDesc(String value) {
        String trimmed = value == null ? "" : value.trim();
        if ("ASC".equalsIgnoreCase(trimmed)) {
            return "ASC";
        }
        if ("DESC".equalsIgnoreCase(trimmed)) {
            return "DESC";
        }
        throw new IllegalArgumentException("Invalid SUNDB index sort direction: " + value);
    }

    /**
     * Validates one complete DEFAULT expression. Nested calls, sequence
     * expressions, operators, and quoted literals remain available while
     * statement terminators, comments, unbalanced delimiters, top-level commas,
     * and trailing column clauses are rejected.
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
     * Validates a fallback type that is not one of the plugin's known enum
     * values. Qualified user-defined types and numeric precision/scale clauses
     * are retained, but column clauses and statement syntax fail closed.
     */
    public static String requireColumnTypeExpression(String columnType) {
        String trimmed = StringUtils.trimToNull(columnType);
        if (trimmed == null) {
            throw invalid("column type", columnType);
        }
        scanExpression(trimmed, true, "column type");
        return trimmed;
    }

    /**
     * Validates a VARCHAR size unit: only CHAR/BYTE are legal. Anything else is
     * rejected to block DDL injection.
     */
    public static String requireUnit(String unit) {
        String trimmed = StringUtils.trimToEmpty(unit);
        if ("CHAR".equalsIgnoreCase(trimmed)) {
            return "CHAR";
        }
        if ("BYTE".equalsIgnoreCase(trimmed)) {
            return "BYTE";
        }
        throw new IllegalArgumentException("Unsupported VARCHAR unit: " + unit);
    }

    public static String requireConstraintType(String constraintType) {
        String normalized = StringUtils.trimToEmpty(constraintType).toUpperCase(Locale.ROOT);
        if (CONSTRAINT_TYPES.contains(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported SUNDB constraint type: " + constraintType);
    }

    public static String requireNullOrder(String nullOrder) {
        String normalized = StringUtils.trimToEmpty(nullOrder).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "";
        }
        if ("NULLS FIRST".equals(normalized) || "NULL FIRST".equals(normalized)) {
            return "NULLS FIRST";
        }
        if ("NULLS LAST".equals(normalized) || "NULL LAST".equals(normalized)) {
            return "NULLS LAST";
        }
        throw new IllegalArgumentException("Unsupported SUNDB index null ordering: " + nullOrder);
    }

    private static void scanExpression(String expression, boolean typeExpression, String description) {
        Deque<Character> parentheses = new ArrayDeque<>();
        List<String> topLevelWords = new ArrayList<>();
        boolean sawToken = false;
        boolean topLevelLiteralEnded = false;
        boolean intervalLiteralEnded = false;
        boolean readingIntervalQualifier = false;
        List<String> intervalQualifierWords = new ArrayList<>();

        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            sawToken = true;

            if (!typeExpression && isAlternativeQuoteStart(expression, i)) {
                if (parentheses.isEmpty() && topLevelLiteralEnded) {
                    throw invalid(description, expression);
                }
                i = scanAlternativeQuote(expression, i, description);
                if (parentheses.isEmpty()) {
                    topLevelLiteralEnded = true;
                    intervalLiteralEnded = lastWordIsInterval(topLevelWords);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                if (typeExpression && c == '\'') {
                    throw invalid(description, expression);
                }
                if (!typeExpression && parentheses.isEmpty() && topLevelLiteralEnded) {
                    throw invalid(description, expression);
                }
                i = scanQuoted(expression, i, c, description);
                if (!typeExpression && parentheses.isEmpty() && c == '\'') {
                    topLevelLiteralEnded = true;
                    intervalLiteralEnded = lastWordIsInterval(topLevelWords);
                }
                continue;
            }
            if (topLevelLiteralEnded && parentheses.isEmpty()) {
                if (Character.isLetterOrDigit(c) || c == '_') {
                    if (!intervalLiteralEnded || !Character.isLetter(c)) {
                        throw invalid(description, expression);
                    }
                    readingIntervalQualifier = true;
                    intervalQualifierWords.clear();
                } else if (intervalLiteralEnded) {
                    throw invalid(description, expression);
                }
                topLevelLiteralEnded = false;
                intervalLiteralEnded = false;
            }
            if (readingIntervalQualifier && !Character.isLetter(c) && c != '_') {
                if (!INTERVAL_QUALIFIERS.contains(intervalQualifierWords)) {
                    throw invalid(description, expression);
                }
                readingIntervalQualifier = false;
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
                if (readingIntervalQualifier) {
                    intervalQualifierWords.add(word);
                    if (!isIntervalQualifierPrefix(intervalQualifierWords)) {
                        throw invalid(description, expression);
                    }
                }
                if (parentheses.isEmpty()) {
                    topLevelWords.add(word);
                }
                i = wordEnd - 1;
            }
        }

        if (!sawToken || !parentheses.isEmpty() || intervalLiteralEnded
                || readingIntervalQualifier && !INTERVAL_QUALIFIERS.contains(intervalQualifierWords)) {
            throw invalid(description, expression);
        }
        for (String word : topLevelWords) {
            if (COLUMN_CLAUSE_KEYWORDS.contains(word)
                    || typeExpression && ("NOT".equals(word) || "NULL".equals(word))
                    || !typeExpression && "NULL".equals(word)
                    && !(topLevelWords.size() == 1 && "NULL".equalsIgnoreCase(expression))) {
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

    private static boolean lastWordIsInterval(List<String> topLevelWords) {
        return !topLevelWords.isEmpty() && "INTERVAL".equals(topLevelWords.get(topLevelWords.size() - 1));
    }

    private static boolean isIntervalQualifierPrefix(List<String> words) {
        return INTERVAL_QUALIFIERS.stream().anyMatch(qualifier -> qualifier.size() >= words.size()
                && qualifier.subList(0, words.size()).equals(words));
    }

    private static boolean startsWith(String value, int offset, String candidate) {
        return offset + candidate.length() <= value.length() && value.startsWith(candidate, offset);
    }

    private static IllegalArgumentException invalid(String description, String value) {
        return new IllegalArgumentException("Invalid SUNDB " + description + ": " + value);
    }
}

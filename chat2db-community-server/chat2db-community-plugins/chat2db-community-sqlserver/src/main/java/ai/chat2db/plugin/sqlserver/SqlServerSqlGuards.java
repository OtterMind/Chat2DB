package ai.chat2db.plugin.sqlserver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import ai.chat2db.plugin.sqlserver.identifier.SqlServerIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

/**
 * Validation helpers for non-escapable SQL positions in SQL Server DDL
 * generation (collation names embedded as bare tokens).
 * Escaping itself lives in {@link SqlServerIdentifierProcessor}.
 */
public final class SqlServerSqlGuards {

    /**
     * Conservative allow-list for collation names reported by JDBC metadata or
     * user input before they are embedded as bare tokens in generated DDL.
     */
    private static final Pattern COLLATION_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");
    private static final Set<String> MULTIWORD_TYPE_NAMES = Set.of(
            "BINARY VARYING",
            "CHAR VARYING",
            "CHARACTER VARYING",
            "DOUBLE PRECISION",
            "NATIONAL CHAR",
            "NATIONAL CHAR VARYING",
            "NATIONAL CHARACTER",
            "NATIONAL CHARACTER VARYING");
    private static final Set<String> COLUMN_CLAUSE_KEYWORDS = Set.of(
            "CHECK", "CONSTRAINT", "DEFAULT", "ENCRYPTED", "IDENTITY", "MASKED",
            "PERSISTED", "REFERENCES", "ROWGUIDCOL", "SPARSE", "UNIQUE");

    private SqlServerSqlGuards() {
    }

    /**
     * Validates a collation name before it is embedded into generated DDL.
     * Returns the collation unchanged when it matches the allow-list; throws
     * otherwise (fail closed).
     */
    public static String validateCollation(String collation) {
        if (collation == null || !COLLATION_NAME_PATTERN.matcher(collation).matches()) {
            throw new IllegalArgumentException("Invalid SQL Server collation name: " + collation);
        }
        return collation;
    }

    /**
     * Accepts a SQL Server built-in or schema-qualified user-defined type,
     * with an optional balanced argument list such as {@code decimal(18, 2)},
     * {@code nvarchar(max)}, or {@code xml(CONTENT [dbo].[Collection])}.
     */
    public static String requireColumnTypeExpression(String columnType) {
        if (StringUtils.isBlank(columnType)) {
            throw invalid("column type", columnType);
        }
        String expression = columnType.trim();
        int argumentsStart = findArgumentsStart(expression);
        String typeName = argumentsStart < 0 ? expression : expression.substring(0, argumentsStart).trim();
        if (!isTypeName(typeName)) {
            throw invalid("column type", columnType);
        }
        if (argumentsStart < 0) {
            return expression;
        }
        int argumentsEnd = scanExpression(expression, argumentsStart, true, false, "column type");
        if (skipWhitespace(expression, argumentsEnd + 1) != expression.length()) {
            throw invalid("column type", columnType);
        }
        return expression;
    }

    /**
     * Validates one DEFAULT expression without rewriting string literals or
     * metadata-serialized function calls.
     */
    public static String requireDefaultExpression(String defaultValue) {
        if (StringUtils.isBlank(defaultValue)) {
            throw invalid("default expression", defaultValue);
        }
        String expression = defaultValue.trim();
        scanExpression(expression, 0, false, true, "default expression");
        return expression;
    }

    private static int findArgumentsStart(String expression) {
        boolean inBracketIdentifier = false;
        boolean inQuotedIdentifier = false;
        for (int i = 0; i < expression.length(); i++) {
            char current = expression.charAt(i);
            char next = i + 1 < expression.length() ? expression.charAt(i + 1) : '\0';
            if (inBracketIdentifier) {
                if (current == ']' && next == ']') {
                    i++;
                } else if (current == ']') {
                    inBracketIdentifier = false;
                }
                continue;
            }
            if (inQuotedIdentifier) {
                if (current == '"' && next == '"') {
                    i++;
                } else if (current == '"') {
                    inQuotedIdentifier = false;
                }
                continue;
            }
            if (current == '[') {
                inBracketIdentifier = true;
            } else if (current == '"') {
                inQuotedIdentifier = true;
            } else if (current == '(') {
                return i;
            }
        }
        if (inBracketIdentifier || inQuotedIdentifier) {
            throw invalid("column type", expression);
        }
        return -1;
    }

    private static boolean isTypeName(String typeName) {
        String normalized = StringUtils.normalizeSpace(typeName).toUpperCase(Locale.ROOT);
        if (MULTIWORD_TYPE_NAMES.contains(normalized)) {
            return true;
        }
        int offset = 0;
        while (offset < typeName.length()) {
            offset = skipWhitespace(typeName, offset);
            if (offset >= typeName.length()) {
                return false;
            }
            char first = typeName.charAt(offset);
            if (first == '[' || first == '"') {
                char close = first == '[' ? ']' : '"';
                boolean closed = false;
                offset++;
                while (offset < typeName.length()) {
                    char current = typeName.charAt(offset);
                    if (current == close) {
                        if (offset + 1 < typeName.length() && typeName.charAt(offset + 1) == close) {
                            offset += 2;
                            continue;
                        }
                        offset++;
                        closed = true;
                        break;
                    }
                    offset++;
                }
                if (!closed) {
                    return false;
                }
            } else {
                if (!(Character.isLetter(first) || first == '_' || first == '#' || first == '@')) {
                    return false;
                }
                offset++;
                while (offset < typeName.length()) {
                    char current = typeName.charAt(offset);
                    if (!(Character.isLetterOrDigit(current) || current == '_' || current == '$'
                            || current == '#' || current == '@')) {
                        break;
                    }
                    offset++;
                }
            }
            offset = skipWhitespace(typeName, offset);
            if (offset == typeName.length()) {
                return true;
            }
            if (typeName.charAt(offset) != '.') {
                return false;
            }
            offset++;
        }
        return false;
    }

    private static int scanExpression(String expression, int start, boolean stopAtRootClose,
                                      boolean rejectTopLevelComma, String description) {
        Deque<Character> delimiters = new ArrayDeque<>();
        List<String> topLevelWords = new ArrayList<>();
        boolean inString = false;
        boolean inBracketIdentifier = false;
        boolean inQuotedIdentifier = false;
        for (int i = start; i < expression.length(); i++) {
            char current = expression.charAt(i);
            char next = i + 1 < expression.length() ? expression.charAt(i + 1) : '\0';
            if (inString) {
                if (current == '\'' && next == '\'') {
                    i++;
                } else if (current == '\'') {
                    inString = false;
                }
                continue;
            }
            if (inBracketIdentifier) {
                if (current == ']' && next == ']') {
                    i++;
                } else if (current == ']') {
                    inBracketIdentifier = false;
                }
                continue;
            }
            if (inQuotedIdentifier) {
                if (current == '"' && next == '"') {
                    i++;
                } else if (current == '"') {
                    inQuotedIdentifier = false;
                }
                continue;
            }

            if (current == '\'') {
                inString = true;
                continue;
            }
            if (current == '[') {
                inBracketIdentifier = true;
                continue;
            }
            if (current == '"') {
                inQuotedIdentifier = true;
                continue;
            }
            if (current == ';' || current == '\n' || current == '\r'
                    || startsWith(expression, i, "--")
                    || startsWith(expression, i, "/*")
                    || startsWith(expression, i, "*/")) {
                throw invalid(description, expression);
            }
            if (current == '(' || current == '{') {
                delimiters.push(current);
                continue;
            }
            if (current == ')' || current == '}') {
                if (delimiters.isEmpty() || !matches(delimiters.pop(), current)) {
                    throw invalid(description, expression);
                }
                if (stopAtRootClose && delimiters.isEmpty()) {
                    return i;
                }
                continue;
            }
            if (rejectTopLevelComma && current == ',' && delimiters.isEmpty()) {
                throw invalid(description, expression);
            }
            if (Character.isISOControl(current)) {
                throw invalid(description, expression);
            }
            if (rejectTopLevelComma && delimiters.isEmpty()
                    && (Character.isLetter(current) || current == '_')) {
                int wordEnd = i + 1;
                while (wordEnd < expression.length() && isWordCharacter(expression.charAt(wordEnd))) {
                    wordEnd++;
                }
                topLevelWords.add(expression.substring(i, wordEnd).toUpperCase(Locale.ROOT));
                i = wordEnd - 1;
            }
        }
        if (inString || inBracketIdentifier || inQuotedIdentifier || !delimiters.isEmpty() || stopAtRootClose) {
            throw invalid(description, expression);
        }
        rejectColumnClauseTokens(topLevelWords, description, expression);
        return expression.length();
    }

    private static void rejectColumnClauseTokens(List<String> words, String description, String expression) {
        for (String word : words) {
            if (COLUMN_CLAUSE_KEYWORDS.contains(word)) {
                throw invalid(description, expression);
            }
        }
        for (int i = 0; i + 1 < words.size(); i++) {
            String first = words.get(i);
            String second = words.get(i + 1);
            if (("NOT".equals(first) && "NULL".equals(second))
                    || ("PRIMARY".equals(first) && "KEY".equals(second))
                    || ("GENERATED".equals(first) && "ALWAYS".equals(second))
                    || ("WITH".equals(first) && "VALUES".equals(second))
                    || ("FOR".equals(first) && "REPLICATION".equals(second))) {
                throw invalid(description, expression);
            }
        }
    }

    private static boolean isWordCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$'
                || value == '#' || value == '@';
    }

    private static int skipWhitespace(String value, int offset) {
        int current = offset;
        while (current < value.length() && Character.isWhitespace(value.charAt(current))) {
            current++;
        }
        return current;
    }

    private static boolean startsWith(String value, int offset, String candidate) {
        return offset + candidate.length() <= value.length() && value.startsWith(candidate, offset);
    }

    private static boolean matches(char open, char close) {
        return open == '(' && close == ')' || open == '{' && close == '}';
    }

    private static IllegalArgumentException invalid(String description, String value) {
        return new IllegalArgumentException("Invalid SQL Server " + description + ": " + value);
    }
}

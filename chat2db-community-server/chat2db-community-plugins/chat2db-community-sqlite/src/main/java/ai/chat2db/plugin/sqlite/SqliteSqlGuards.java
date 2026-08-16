package ai.chat2db.plugin.sqlite;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import ai.chat2db.plugin.sqlite.identifier.SqliteIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

/**
 * Validation helpers for non-escapable SQL positions in SQLite DDL generation
 * (collation/charset keyword names, free-text column type names, column default
 * expressions) and for {@code --} line comments. Escaping itself lives in
 * {@link SqliteIdentifierProcessor}.
 */
public final class SqliteSqlGuards {

    /**
     * Conservative allow-list for names embedded into keyword positions where quoting
     * is not possible (COLLATE, CHARACTER SET). Accepts BINARY/NOCASE/RTRIM and
     * simple custom collation names; rejects anything that could break out of the DDL.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    /**
     * Conservative allow-list for free-text column type names
     * (e.g. {@code VARCHAR(255)}, {@code NUMERIC(10,2)}, {@code DOUBLE PRECISION}).
     * Anything else is rejected so a hostile type cannot smuggle SQL into generated DDL.
     */
    private static final Set<String> COLUMN_CLAUSE_KEYWORDS = Set.of(
            "AUTOINCREMENT", "CHECK", "COLLATE", "CONSTRAINT", "DEFAULT", "GENERATED",
            "NOT", "NULL", "PRIMARY", "REFERENCES", "UNIQUE");
    private static final Set<String> STATEMENT_KEYWORDS = Set.of(
            "ALTER", "ATTACH", "CREATE", "DELETE", "DETACH", "DROP", "INSERT", "PRAGMA",
            "REINDEX", "REPLACE", "SELECT", "UPDATE", "VACUUM");

    private SqliteSqlGuards() {
    }

    /**
     * Validates a name embedded into a keyword position (collation, charset) against a
     * conservative allow-list. Returns the name unchanged when safe; throws otherwise
     * (fail closed).
     *
     * @throws IllegalArgumentException if the name contains unexpected characters
     */
    public static String requireSafeName(String name, String what) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsafe " + what + " name: " + name);
        }
        return name;
    }

    /**
     * Validates a free-text column type name before it is embedded into generated DDL.
     * Returns the type name unchanged when it matches a conservative allow-list;
     * throws otherwise (fail closed).
     *
     * @throws IllegalArgumentException if the type name contains unexpected characters
     */
    public static String requireSafeTypeName(String typeName) {
        String expression = StringUtils.trimToNull(typeName);
        if (expression == null) {
            return null;
        }
        scanExpression(expression, true, "column type");
        return expression;
    }

    /**
     * Validates one SQLite DEFAULT expression while preserving its SQL semantics. Nested
     * functions and quoted literals are accepted when delimiters are balanced; comments,
     * statement boundaries, top-level commas, and appended column constraints are rejected.
     * Returns an empty string for {@code null}.
     */
    public static String escapeColumnDefault(String columnDefault) {
        if (columnDefault == null) {
            return "";
        }
        String trimmed = columnDefault.trim();
        if (trimmed.isEmpty()) {
            throw invalid("DEFAULT expression", columnDefault);
        }
        if ("NULL".equalsIgnoreCase(trimmed)) {
            return trimmed;
        }
        scanExpression(trimmed, false, "DEFAULT expression");
        return trimmed;
    }

    /**
     * Neutralises a comment embedded after a {@code --} line-comment marker by
     * flattening CR/LF to spaces, so the comment cannot break out onto a new,
     * executable line. Returns an empty string for {@code null}.
     */
    public static String sanitizeLineComment(String comment) {
        if (comment == null) {
            return "";
        }
        return StringUtils.replaceEach(comment,
                new String[]{"\r", "\n", "\u0085", "\u2028", "\u2029"},
                new String[]{" ", " ", " ", " ", " "});
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
            if (c == ',' && parentheses.isEmpty()) {
                throw invalid(description, expression);
            }
            if (typeExpression && !isTypeCharacter(c, !parentheses.isEmpty())) {
                throw invalid(description, expression);
            }
            if (Character.isLetter(c) || c == '_') {
                int wordEnd = i + 1;
                while (wordEnd < expression.length() && isWordCharacter(expression.charAt(wordEnd))) {
                    wordEnd++;
                }
                String word = expression.substring(i, wordEnd).toUpperCase(Locale.ROOT);
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
            if (STATEMENT_KEYWORDS.contains(word)
                    || COLUMN_CLAUSE_KEYWORDS.contains(word)
                    || typeExpression && "NULL".equals(word)) {
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

    private static boolean isTypeCharacter(char c, boolean insideParentheses) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.'
                || c == ',' && insideParentheses
                || (c == '+' || c == '-') && insideParentheses;
    }

    private static boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean startsWith(String value, int offset, String candidate) {
        return offset + candidate.length() <= value.length() && value.startsWith(candidate, offset);
    }

    private static IllegalArgumentException invalid(String description, String value) {
        return new IllegalArgumentException("Invalid SQLite " + description + ": " + value);
    }
}

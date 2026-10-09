package ai.chat2db.spi.util;

import ai.chat2db.community.domain.api.model.sql.SqlParameterType;
import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds {@code :name} and {@code ?} parameter placeholders in a SQL statement and
 * prepares the statement for JDBC parameter binding.
 * <p>
 * Placeholders inside string literals, quoted identifiers and comments are
 * ignored. {@code ::} casts, {@code :=} assignments and colons that follow an
 * identifier character (for example {@code a:b}) are not placeholders.
 * Statements that start with {@code CREATE}, {@code ALTER} or {@code DROP} never
 * contain placeholders, so trigger bodies that use {@code :NEW} and {@code :OLD}
 * keep running as plain SQL.
 * <p>
 * Parameters move through three steps. {@link #normalize} validates the supplied
 * values against the whole SQL and renames positional {@code ?} placeholders to
 * synthetic {@code :__p1}, {@code :__p2}, ... names, so statement splitting,
 * paging and COUNT rewriting only ever see named parameters, which keep their
 * identity when a rewrite drops or reorders expressions. {@link #compile} then
 * turns each final SQL into JDBC {@code ?} markers plus the values in that SQL's
 * own order, and {@link #restorePositional} turns synthetic names back into
 * {@code ?} for the SQL reported to the user. Values are never written into the
 * SQL text; they are bound through {@link java.sql.PreparedStatement}.
 * <p>
 * The client mirrors these rules in
 * {@code chat2db-community-client/src/utils/sqlParameters.ts}.
 */
public final class SqlParameterParser {

    private static final Set<String> DDL_KEYWORDS = Set.of("CREATE", "ALTER", "DROP");

    /**
     * Prefix of the synthetic names given to positional {@code ?} placeholders.
     */
    public static final String POSITIONAL_NAME_PREFIX = "__p";

    private static final Pattern POSITIONAL_NAME = Pattern.compile(Pattern.quote(POSITIONAL_NAME_PREFIX) + "[1-9][0-9]*");

    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    private SqlParameterParser() {
    }

    public enum Style {
        NAMED,
        POSITIONAL
    }

    /**
     * One placeholder occurrence; {@code start} and {@code end} delimit it in the SQL text.
     */
    public record Placeholder(Style style, String name, int start, int end) {
    }

    /**
     * SQL that uses only JDBC {@code ?} markers plus the values to bind, in marker order.
     */
    public record BoundSql(String sql, List<SqlParameterValue> values) {

        public BoundSql {
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }

        @Override
        public String toString() {
            // Parameter values stay out of logs and error messages.
            return "BoundSql[sql=" + sql + ", parameterCount=" + values.size() + "]";
        }
    }

    /**
     * SQL whose placeholders are all named, plus the value for every name.
     *
     * @param positional {@code true} when the names are synthetic {@code __pN} names
     *                   given to {@code ?} placeholders.
     */
    public record NormalizedSql(String sql, Map<String, SqlParameterValue> parameters, boolean positional) {

        public NormalizedSql {
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }

        @Override
        public String toString() {
            // Parameter values stay out of logs and error messages.
            return "NormalizedSql[sql=" + sql + ", parameters=" + parameters.keySet() + ", positional="
                    + positional + "]";
        }
    }

    /**
     * Returns every placeholder occurrence in source order.
     */
    public static List<Placeholder> findPlaceholders(String sql, SqlParameterSyntax syntax) {
        Objects.requireNonNull(syntax, "syntax");
        if (StringUtils.isBlank(sql) || startsWithDdlKeyword(sql, syntax)) {
            return List.of();
        }
        List<Placeholder> placeholders = new ArrayList<>();
        int length = sql.length();
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            char next = charAt(sql, i + 1);
            int skipped = skipNonCode(sql, i, syntax);
            if (skipped > i) {
                i = skipped;
                continue;
            }
            if (c == '?') {
                if ((syntax.doubledQuestionMarkEscape() && next == '?')
                        || (syntax.questionMarkOperators() && (next == '|' || next == '&'))) {
                    i += 2;
                    continue;
                }
                placeholders.add(new Placeholder(Style.POSITIONAL, null, i, i + 1));
                i++;
                continue;
            }
            if (c == ':') {
                if (next == ':') {
                    i += 2;
                    continue;
                }
                char previous = charAt(sql, i - 1);
                if (isNameStart(next) && !isIdentifierPart(previous)) {
                    int end = i + 1;
                    while (end < length && isNamePart(sql.charAt(end))) {
                        end++;
                    }
                    placeholders.add(new Placeholder(Style.NAMED, sql.substring(i + 1, end), i, end));
                    i = end;
                    continue;
                }
                i++;
                continue;
            }
            if (isIdentifierPart(c)) {
                i = skipWord(sql, i);
                continue;
            }
            i++;
        }
        return placeholders;
    }

    /**
     * Returns {@code true} when the SQL contains at least one placeholder.
     */
    public static boolean hasPlaceholders(String sql, SqlParameterSyntax syntax) {
        return !findPlaceholders(sql, syntax).isEmpty();
    }

    /**
     * Replaces every placeholder with a numeric literal of the same length
     * ({@code :id} becomes {@code 000}), so a syntax checker accepts the statement,
     * every other character keeps its line and column, and the literal is a single
     * token spanning the whole placeholder.
     */
    public static String maskPlaceholdersAsLiterals(String sql, SqlParameterSyntax syntax) {
        List<Placeholder> placeholders = findPlaceholders(sql, syntax);
        if (placeholders.isEmpty()) {
            return sql;
        }
        StringBuilder masked = new StringBuilder(sql);
        for (Placeholder placeholder : placeholders) {
            for (int i = placeholder.start(); i < placeholder.end(); i++) {
                masked.setCharAt(i, '0');
            }
        }
        return masked.toString();
    }

    /**
     * Validates the supplied values against every placeholder in {@code sql} and
     * renames positional {@code ?} placeholders to synthetic {@code :__pN} names.
     *
     * @param named      values for {@code :name} placeholders, keyed by name.
     * @param positional values for {@code ?} placeholders, in appearance order.
     * @return the SQL with named placeholders only, or {@code null} when no values
     * were supplied.
     * @throws BusinessException when the SQL has no placeholders or mixes both
     *                           styles, when the values do not match the
     *                           placeholders, or when a value is missing or invalid
     *                           for its type.
     */
    public static NormalizedSql normalize(String sql, Map<String, SqlParameterValue> named,
                                          List<SqlParameterValue> positional, SqlParameterSyntax syntax) {
        boolean hasNamedValues = named != null && !named.isEmpty();
        boolean hasPositionalValues = positional != null && !positional.isEmpty();
        if (!hasNamedValues && !hasPositionalValues) {
            return null;
        }
        if (hasNamedValues && hasPositionalValues) {
            throw new BusinessException("sqlParameter.invalid");
        }
        List<Placeholder> placeholders = findPlaceholders(sql, syntax);
        if (placeholders.isEmpty()) {
            throw new BusinessException("sqlParameter.notFound");
        }
        boolean namedPlaceholders = placeholders.stream().anyMatch(placeholder -> placeholder.style() == Style.NAMED);
        boolean positionalPlaceholders = placeholders.stream()
                .anyMatch(placeholder -> placeholder.style() == Style.POSITIONAL);
        if (namedPlaceholders && positionalPlaceholders) {
            throw new BusinessException("sqlParameter.mixedStyles");
        }
        if (namedPlaceholders != hasNamedValues) {
            throw new BusinessException("sqlParameter.invalid");
        }
        return hasNamedValues ? normalizeNamed(sql, placeholders, named) : normalizePositional(sql, placeholders,
                positional);
    }

    private static NormalizedSql normalizeNamed(String sql, List<Placeholder> placeholders,
                                                Map<String, SqlParameterValue> values) {
        Set<String> names = new LinkedHashSet<>();
        placeholders.forEach(placeholder -> names.add(placeholder.name()));
        for (String name : names) {
            if (!values.containsKey(name)) {
                throw new BusinessException("sqlParameter.missing", new Object[]{name});
            }
            validateValue(name, values.get(name));
        }
        if (!names.containsAll(values.keySet())) {
            throw new BusinessException("sqlParameter.invalid");
        }
        return new NormalizedSql(sql, values, false);
    }

    private static NormalizedSql normalizePositional(String sql, List<Placeholder> placeholders,
                                                     List<SqlParameterValue> values) {
        if (values.size() < placeholders.size()) {
            throw new BusinessException("sqlParameter.missing", new Object[]{values.size() + 1});
        }
        if (values.size() > placeholders.size()) {
            throw new BusinessException("sqlParameter.invalid");
        }
        Map<String, SqlParameterValue> valuesByName = new LinkedHashMap<>();
        StringBuilder normalized = new StringBuilder(sql.length() + placeholders.size() * 5);
        int copiedUntil = 0;
        for (int index = 0; index < placeholders.size(); index++) {
            Placeholder placeholder = placeholders.get(index);
            SqlParameterValue value = values.get(index);
            validateValue(String.valueOf(index + 1), value);
            String name = POSITIONAL_NAME_PREFIX + (index + 1);
            normalized.append(sql, copiedUntil, placeholder.start());
            // Keep the synthetic name a separate token, as the scanner requires.
            if (isIdentifierPart(charAt(sql, placeholder.start() - 1))) {
                normalized.append(' ');
            }
            normalized.append(':').append(name);
            if (isNamePart(charAt(sql, placeholder.end()))) {
                normalized.append(' ');
            }
            copiedUntil = placeholder.end();
            valuesByName.put(name, value);
        }
        normalized.append(sql, copiedUntil, sql.length());
        return new NormalizedSql(normalized.toString(), valuesByName, true);
    }

    /**
     * Turns one final SQL into JDBC {@code ?} markers plus the values in that SQL's
     * own placeholder order. Values whose names the SQL no longer references are
     * ignored, because COUNT rewriting can drop the projection and ORDER BY.
     *
     * @throws BusinessException when the SQL references a name without a value or
     *                           contains a positional {@code ?}.
     */
    public static BoundSql compile(String sql, Map<String, SqlParameterValue> parameters, SqlParameterSyntax syntax) {
        List<Placeholder> placeholders = findPlaceholders(sql, syntax);
        StringBuilder jdbcSql = new StringBuilder(sql.length());
        List<SqlParameterValue> orderedValues = new ArrayList<>(placeholders.size());
        int copiedUntil = 0;
        for (Placeholder placeholder : placeholders) {
            if (placeholder.style() != Style.NAMED) {
                throw new BusinessException("sqlParameter.mixedStyles");
            }
            SqlParameterValue value = parameters == null ? null : parameters.get(placeholder.name());
            if (value == null) {
                throw new BusinessException("sqlParameter.missing", new Object[]{placeholder.name()});
            }
            jdbcSql.append(sql, copiedUntil, placeholder.start()).append('?');
            copiedUntil = placeholder.end();
            orderedValues.add(value);
        }
        jdbcSql.append(sql, copiedUntil, sql.length());
        return new BoundSql(jdbcSql.toString(), orderedValues);
    }

    /**
     * Turns the synthetic {@code :__pN} names that {@link #normalize} gave to
     * positional placeholders back into {@code ?}.
     */
    public static String restorePositional(String sql, SqlParameterSyntax syntax) {
        List<Placeholder> placeholders = findPlaceholders(sql, syntax);
        StringBuilder restored = new StringBuilder(sql.length());
        int copiedUntil = 0;
        for (Placeholder placeholder : placeholders) {
            if (placeholder.style() == Style.NAMED && POSITIONAL_NAME.matcher(placeholder.name()).matches()) {
                restored.append(sql, copiedUntil, placeholder.start()).append('?');
                copiedUntil = placeholder.end();
            }
        }
        return restored.append(sql, copiedUntil, sql.length()).toString();
    }

    /**
     * Checks that a value has a type and that its text is valid for that type.
     * Empty text is a valid {@link SqlParameterType#STRING}.
     */
    private static void validateValue(String label, SqlParameterValue value) {
        if (value == null || value.getType() == null) {
            throw new BusinessException("sqlParameter.invalidType", new Object[]{label});
        }
        String text = value.getValue();
        boolean valid = switch (value.getType()) {
            case STRING -> text != null;
            case NUMBER -> text != null && NUMBER.matcher(text).matches();
            case BOOLEAN -> "true".equals(text) || "false".equals(text);
            case NULL -> text == null;
        };
        if (!valid) {
            throw new BusinessException("sqlParameter.invalidValue", new Object[]{label, value.getType().name()});
        }
    }

    /**
     * Parses the text of a validated {@link SqlParameterType#NUMBER} value.
     */
    public static BigDecimal parseNumber(String text) {
        if (text == null || !NUMBER.matcher(text).matches()) {
            throw new BusinessException("sqlParameter.invalidValue", new Object[]{"", SqlParameterType.NUMBER.name()});
        }
        return new BigDecimal(text);
    }

    private static boolean startsWithDdlKeyword(String sql, SqlParameterSyntax syntax) {
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '(' || c == ';') {
                i++;
                continue;
            }
            int skipped = skipComment(sql, i, syntax);
            if (skipped > i) {
                i = skipped;
                continue;
            }
            break;
        }
        if (i >= length || !isNameStart(sql.charAt(i))) {
            return false;
        }
        String keyword = sql.substring(i, skipWord(sql, i)).toUpperCase(Locale.ROOT);
        return DDL_KEYWORDS.contains(keyword);
    }

    /**
     * Returns the index after a comment, literal or quoted identifier starting at
     * {@code i}, or {@code i} when none starts there.
     */
    private static int skipNonCode(String sql, int i, SqlParameterSyntax syntax) {
        int skipped = skipComment(sql, i, syntax);
        if (skipped > i) {
            return skipped;
        }
        char c = sql.charAt(i);
        char next = charAt(sql, i + 1);
        char previous = charAt(sql, i - 1);
        switch (c) {
            case '\'':
                return skipQuoted(sql, i, '\'', syntax.backslashEscapes());
            case '"':
                return skipQuoted(sql, i, '"', syntax.backslashEscapes());
            case '`':
                return skipQuoted(sql, i, '`', false);
            case '[':
                return syntax.bracketIdentifiers() ? skipBracketIdentifier(sql, i) : i;
            case '$':
                return syntax.dollarQuotedStrings() && !isIdentifierPart(previous) ? skipDollarQuoted(sql, i) : i;
            default:
                break;
        }
        if (isIdentifierPart(previous)) {
            return i;
        }
        if (syntax.dollarQuotedStrings() && (c == 'E' || c == 'e') && next == '\'') {
            return skipQuoted(sql, i + 1, '\'', true);
        }
        if (syntax.alternativeQuotedStrings()) {
            int quote = i;
            if ((c == 'N' || c == 'n') && (next == 'Q' || next == 'q')) {
                quote++;
            }
            if ((sql.charAt(quote) == 'Q' || sql.charAt(quote) == 'q') && charAt(sql, quote + 1) == '\'') {
                return skipAlternativeQuoted(sql, quote + 1);
            }
        }
        return i;
    }

    private static int skipComment(String sql, int i, SqlParameterSyntax syntax) {
        char c = sql.charAt(i);
        char next = charAt(sql, i + 1);
        if (c == '-' && next == '-') {
            return skipLine(sql, i + 2);
        }
        if (c == '#' && syntax.hashLineComments()) {
            return skipLine(sql, i + 1);
        }
        if (c == '/' && next == '*') {
            return skipBlockComment(sql, i, syntax.nestedBlockComments());
        }
        return i;
    }

    private static int skipLine(String sql, int i) {
        int length = sql.length();
        while (i < length && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String sql, int start, boolean nested) {
        int length = sql.length();
        int depth = 1;
        int i = start + 2;
        while (i < length) {
            char c = sql.charAt(i);
            char next = charAt(sql, i + 1);
            if (nested && c == '/' && next == '*') {
                depth++;
                i += 2;
            } else if (c == '*' && next == '/') {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        return length;
    }

    private static int skipQuoted(String sql, int start, char quote, boolean backslashEscapes) {
        int length = sql.length();
        int i = start + 1;
        while (i < length) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (charAt(sql, i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return length;
    }

    private static int skipBracketIdentifier(String sql, int start) {
        int length = sql.length();
        int i = start + 1;
        while (i < length) {
            if (sql.charAt(i) == ']') {
                if (charAt(sql, i + 1) == ']') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return length;
    }

    private static int skipDollarQuoted(String sql, int start) {
        int i = start + 1;
        if (charAt(sql, i) != '$') {
            if (!isNameStart(charAt(sql, i))) {
                return start;
            }
            while (isNamePart(charAt(sql, i))) {
                i++;
            }
            if (charAt(sql, i) != '$') {
                return start;
            }
        }
        String tag = sql.substring(start, i + 1);
        int close = sql.indexOf(tag, i + 1);
        return close < 0 ? sql.length() : close + tag.length();
    }

    private static int skipAlternativeQuoted(String sql, int quoteIndex) {
        int length = sql.length();
        if (quoteIndex + 1 >= length) {
            return length;
        }
        char open = sql.charAt(quoteIndex + 1);
        char close = switch (open) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> open;
        };
        int i = quoteIndex + 2;
        while (i < length) {
            if (sql.charAt(i) == close && charAt(sql, i + 1) == '\'') {
                return i + 2;
            }
            i++;
        }
        return length;
    }

    private static int skipWord(String sql, int start) {
        int i = start;
        while (i < sql.length() && isIdentifierPart(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static char charAt(String sql, int index) {
        return index >= 0 && index < sql.length() ? sql.charAt(index) : '\0';
    }

    private static boolean isNameStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isNamePart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '$' || isNamePart(c);
    }
}

package ai.chat2db.plugin.db2;

import java.util.regex.Pattern;

/**
 * Validation helpers for non-escapable SQL positions in DB2 DDL generation
 * (column default expressions, length units, fallback column type names and
 * index column sort directions). Escaping itself lives in
 * {@link ai.chat2db.plugin.db2.identifier.Db2IdentifierProcessor}.
 */
public final class Db2SqlGuards {

    private static final String STRING_LITERAL_SOURCE = "'(?:''|[^'])*'";
    private static final String IDENTIFIER_SOURCE =
            "(?:[A-Za-z_][A-Za-z0-9_$#@]*|\"(?:\"\"|[^\"])+\")";
    private static final Pattern STRING_LITERAL = Pattern.compile("\\A" + STRING_LITERAL_SOURCE + "\\z");
    private static final Pattern NUMERIC_LITERAL = Pattern.compile(
            "\\A[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?\\z");
    private static final Pattern SIMPLE_CONSTANT = Pattern.compile("(?i)\\A(?:NULL|TRUE|FALSE)\\z");
    private static final Pattern CURRENT_TEMPORAL = Pattern.compile(
            "(?i)\\ACURRENT(?:_|\\s+)(?:DATE|TIME|TIMESTAMP)(?:\\(\\d+\\))?\\z");
    private static final Pattern SPECIAL_REGISTER = Pattern.compile(
            "(?i)\\A(?:USER|SESSION_USER|SYSTEM_USER|CURRENT(?:_|\\s+)(?:USER|SCHEMA|SERVER|SQLID|PATH|"
                    + "TIMEZONE|ROLE|CLIENT_ACCTNG|CLIENT_APPLNAME|CLIENT_USERID|CLIENT_WRKSTNNAME|CLIENT_CORR_TOKEN))\\z");
    private static final Pattern SAFE_NO_ARG_FUNCTION = Pattern.compile(
            "(?i)\\A(?:GENERATE_UNIQUE|EMPTY_BLOB|EMPTY_CLOB|EMPTY_DBCLOB)\\(\\s*\\)\\z");
    private static final Pattern TYPED_LITERAL = Pattern.compile(
            "(?i)\\A(?:DATE|TIME|TIMESTAMP)\\s+" + STRING_LITERAL_SOURCE + "\\z");
    private static final Pattern BINARY_LITERAL = Pattern.compile("(?i)\\AX'[0-9A-F]*'\\z");
    private static final Pattern SEQUENCE_EXPRESSION = Pattern.compile(
            "(?i)\\A(?:NEXT\\s+VALUE|NEXTVAL)\\s+FOR\\s+" + IDENTIFIER_SOURCE
                    + "(?:\\." + IDENTIFIER_SOURCE + ")?\\z");

    /**
     * Conservative allow-list for DB2 length units (e.g. {@code OCTETS}).
     */
    private static final Pattern UNIT_PATTERN = Pattern.compile("\\A[A-Za-z0-9_]+\\z");

    /**
     * Strict shape for fallback column types that are not exact enum matches
     * (e.g. {@code VARCHAR(10)}): letters only, with an optional numeric
     * size/scale suffix.
     */
    private static final Pattern FALLBACK_COLUMN_TYPE_PATTERN = Pattern.compile("\\A[A-Za-z]+(\\(\\d+(,\\d+)?\\))?\\z");

    private Db2SqlGuards() {
    }

    /**
     * Keeps complete DB2 literal and generated-default forms intact while rejecting
     * text that could terminate the surrounding column definition.
     */
    public static String requireDefaultExpression(String defaultValue) {
        if (defaultValue == null) {
            return null;
        }
        String trimmed = defaultValue.trim();
        if (STRING_LITERAL.matcher(trimmed).matches()
                || NUMERIC_LITERAL.matcher(trimmed).matches()
                || SIMPLE_CONSTANT.matcher(trimmed).matches()
                || CURRENT_TEMPORAL.matcher(trimmed).matches()
                || SPECIAL_REGISTER.matcher(trimmed).matches()
                || SAFE_NO_ARG_FUNCTION.matcher(trimmed).matches()
                || TYPED_LITERAL.matcher(trimmed).matches()
                || BINARY_LITERAL.matcher(trimmed).matches()
                || SEQUENCE_EXPRESSION.matcher(trimmed).matches()) {
            return trimmed;
        }
        throw new IllegalArgumentException("Invalid DB2 default value: " + defaultValue);
    }

    /**
     * Validates a length unit before it is embedded into a sized column type.
     * Returns the unit unchanged when it matches the allow-list; throws otherwise.
     */
    public static String requireUnit(String unit) {
        if (!UNIT_PATTERN.matcher(unit).matches()) {
            throw new IllegalArgumentException("Invalid DB2 length unit: " + unit);
        }
        return unit;
    }

    /**
     * Validates a fallback column type expression (a type name that does not match
     * an enum constant, e.g. {@code VARCHAR(10)}) before it is embedded into
     * generated DDL. Returns the type unchanged when it matches the strict shape;
     * throws otherwise.
     */
    public static String requireColumnTypeExpression(String columnType) {
        if (columnType == null || !FALLBACK_COLUMN_TYPE_PATTERN.matcher(columnType).matches()) {
            throw new IllegalArgumentException("Invalid DB2 column type: " + columnType);
        }
        return columnType;
    }

    /**
     * Validates an index column sort direction against the ASC/DESC whitelist.
     * Returns the direction unchanged when whitelisted; throws otherwise.
     */
    public static String requireSortDirection(String ascOrDesc) {
        if (!"ASC".equalsIgnoreCase(ascOrDesc) && !"DESC".equalsIgnoreCase(ascOrDesc)) {
            throw new IllegalArgumentException("Invalid DB2 index column ordering: " + ascOrDesc);
        }
        return ascOrDesc;
    }
}

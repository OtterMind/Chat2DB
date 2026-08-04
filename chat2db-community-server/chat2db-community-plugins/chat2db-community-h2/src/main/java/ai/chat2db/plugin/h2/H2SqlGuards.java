package ai.chat2db.plugin.h2;

import java.sql.Types;
import java.util.regex.Pattern;

import ai.chat2db.plugin.h2.identifier.H2IdentifierProcessor;

/**
 * Validation helpers for non-escapable SQL positions in H2 DDL generation
 * (column type names and column default expressions reported by JDBC metadata).
 * Escaping itself lives in {@link H2IdentifierProcessor}.
 */
public final class H2SqlGuards {

    /**
     * Conservative allow-list for column type names reported by JDBC metadata
     * (e.g. {@code INTEGER}, {@code CHARACTER VARYING}). Anything else is rejected
     * so hostile or corrupt metadata cannot smuggle SQL into generated DDL.
     */
    private static final Pattern SAFE_TYPE_NAME = Pattern.compile(
        "^[A-Za-z][A-Za-z0-9_]*(?:\\s+[A-Za-z][A-Za-z0-9_]*)*$");

    private static final String STRING_LITERAL_SOURCE = "'(?:''|[^'])*'";
    private static final String IDENTIFIER_SOURCE = "(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:\"\"|[^\"])+\")";
    private static final Pattern STRING_LITERAL = Pattern.compile("^" + STRING_LITERAL_SOURCE + "$");
    private static final Pattern NUMERIC_LITERAL = Pattern.compile(
        "^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$");
    private static final Pattern SIMPLE_CONSTANT = Pattern.compile("(?i)^(?:NULL|TRUE|FALSE)$");
    private static final Pattern CURRENT_TEMPORAL = Pattern.compile(
        "(?i)^(?:CURRENT_DATE|CURRENT_TIME|CURRENT_TIMESTAMP|LOCALTIME|LOCALTIMESTAMP)(?:\\(\\d+\\))?$");
    private static final Pattern SAFE_NO_ARG_FUNCTION = Pattern.compile(
        "(?i)^(?:NOW|RANDOM_UUID|UUID)\\(\\s*(?:\\d+)?\\s*\\)$");
    private static final Pattern TYPED_LITERAL = Pattern.compile(
        "(?i)^(?:DATE|TIME(?:\\s+WITH\\s+TIME\\s+ZONE)?|TIMESTAMP(?:\\s+WITH\\s+TIME\\s+ZONE)?|UUID|JSON|GEOMETRY)\\s+"
            + STRING_LITERAL_SOURCE + "$");
    private static final Pattern BINARY_LITERAL = Pattern.compile("(?i)^(?:X|BINARY)\\s*'[0-9A-F]*'$");
    private static final Pattern SEQUENCE_EXPRESSION = Pattern.compile(
        "(?i)^NEXT\\s+VALUE\\s+FOR\\s+" + IDENTIFIER_SOURCE + "(?:\\." + IDENTIFIER_SOURCE + ")?$");

    private H2SqlGuards() {
    }

    /**
     * Validates a column type name obtained from JDBC metadata before it is embedded
     * into generated DDL. Returns the type name unchanged when it matches the
     * allow-list; throws otherwise (fail closed).
     */
    public static String requireSafeTypeName(String typeName) {
        if (typeName != null && !SAFE_TYPE_NAME.matcher(typeName).matches()) {
            throw new IllegalArgumentException("Unsafe column type name from metadata: " + typeName);
        }
        return typeName;
    }

    /**
     * Reconstructs a type declaration from JDBC metadata without treating display width as a
     * type parameter. H2 reports values such as 64 for BIGINT and 26 for TIMESTAMP in
     * COLUMN_SIZE, but those values are not legal declarations for these types.
     */
    public static String renderMetadataType(String typeName, int dataType, int columnSize, int decimalDigits) {
        String safeTypeName = requireSafeTypeName(typeName);
        StringBuilder declaration = new StringBuilder(safeTypeName);
        switch (dataType) {
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.BINARY:
            case Types.VARBINARY:
                appendTypeArguments(declaration, columnSize, null);
                break;
            case Types.DECIMAL:
            case Types.NUMERIC:
                appendTypeArguments(declaration, columnSize, Math.max(decimalDigits, 0));
                break;
            case Types.FLOAT:
                appendTypeArguments(declaration, columnSize, null);
                break;
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                if (decimalDigits > 0) {
                    appendTypeArguments(declaration, decimalDigits, null);
                }
                break;
            default:
                break;
        }
        return declaration.toString();
    }

    private static void appendTypeArguments(StringBuilder declaration, int precision, Integer scale) {
        if (precision <= 0) {
            return;
        }
        declaration.append('(').append(precision);
        if (scale != null) {
            declaration.append(',').append(scale);
        }
        declaration.append(')');
    }

    /**
     * Validates a column default obtained from JDBC metadata. Only complete literal forms and
     * common H2-generated expressions are accepted; invalid input is rejected rather than
     * silently converted into a string literal with different semantics.
     * Returns an empty string for {@code null}.
     */
    public static String escapeColumnDefault(String columnDefault) {
        if (columnDefault == null) {
            return "";
        }
        String trimmed = columnDefault.trim();
        if (STRING_LITERAL.matcher(trimmed).matches()
            || NUMERIC_LITERAL.matcher(trimmed).matches()
            || SIMPLE_CONSTANT.matcher(trimmed).matches()
            || CURRENT_TEMPORAL.matcher(trimmed).matches()
            || SAFE_NO_ARG_FUNCTION.matcher(trimmed).matches()
            || TYPED_LITERAL.matcher(trimmed).matches()
            || BINARY_LITERAL.matcher(trimmed).matches()
            || SEQUENCE_EXPRESSION.matcher(trimmed).matches()) {
            return trimmed;
        }
        throw new IllegalArgumentException("Unsafe H2 column default from metadata: " + columnDefault);
    }
}

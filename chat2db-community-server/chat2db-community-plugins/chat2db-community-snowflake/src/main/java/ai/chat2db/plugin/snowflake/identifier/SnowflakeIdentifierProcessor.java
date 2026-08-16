package ai.chat2db.plugin.snowflake.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Snowflake dialect identifier processor: double-quoted identifiers with embedded-quote
 * doubling, and single-quote doubling for string literals. Shared stateless
 * instance available via {@link #INSTANCE} for call sites without MetaData access.
 * <p>
 * {@link #quoteIdentifier(String)} is the SPI-facing conditional variant: identifiers
 * that are already valid and non-reserved are returned unquoted. {@link #quoteIdentifierAlways(String)}
 * is the unconditional variant reserved for DDL-generation call sites that historically
 * always quoted.
 */
public class SnowflakeIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    public static final SnowflakeIdentifierProcessor INSTANCE = new SnowflakeIdentifierProcessor();

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "ALL", "ALTER", "AND", "ANY", "AS", "BETWEEN", "BY", "CASE", "CAST", "CHECK",
            "COLUMN", "CONNECT", "CREATE", "CROSS", "CURRENT", "DELETE", "DISTINCT", "DROP",
            "ELSE", "EXISTS", "FALSE", "FOLLOWING", "FOR", "FROM", "FULL", "GRANT", "GROUP",
            "HAVING", "ILIKE", "IN", "INCREMENT", "INNER", "INSERT", "INTERSECT", "INTO", "IS",
            "JOIN", "LATERAL", "LEFT", "LIKE", "LOCALTIME", "LOCALTIMESTAMP", "MERGE", "MINUS",
            "NATURAL", "NOT", "NULL", "OF", "ON", "OR", "ORDER", "QUALIFY", "REGEXP", "REVOKE",
            "RIGHT", "RLIKE", "ROW", "ROWS", "SAMPLE", "SELECT", "SET", "SOME", "START", "TABLE",
            "TABLESAMPLE", "THEN", "TO", "TRIGGER", "TRUE", "UNION", "UNIQUE", "UPDATE", "USING",
            "VALUES", "WHEN", "WHENEVER", "WHERE", "WITH");

    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return identifier != null && RESERVED_KEYWORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    /**
     * Conditionally quotes: {@code null} stays {@code null}, blank is returned unchanged,
     * identifiers already valid for the dialect (and not reserved keywords) are returned
     * unquoted; anything else is wrapped via {@link #quoteIdentifierAlways(String)}.
     */
    @Override
    public String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        // Snowflake folds unquoted identifiers to uppercase. Quote names containing lowercase
        // characters so metadata-derived names keep their exact spelling.
        if (isValidIdentifier(identifier) && !containsLowerCase(identifier)
                && !isReservedKeyword(identifier, null, null)) {
            return identifier;
        }
        return quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteIdentifier(String identifier, Integer majorVersion, Integer minorVersion) {
        return quoteIdentifier(identifier);
    }

    @Override
    public String quoteIdentifierIgnoreCase(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        if (isValidIdentifier(identifier) && !isReservedKeyword(identifier, null, null)) {
            return identifier;
        }
        return quoteIdentifierAlways(identifier);
    }

    /**
     * Unconditionally quotes with double quotes and doubles every embedded double quote.
     * Boundary quote characters are raw identifier content, so this method and
     * {@code removeIdentifierQuote} round-trip the exact input. For DDL-generation call sites only.
     */
    @Override
    public String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "\"" + escapeIdentifier(identifier) + "\"";
    }

    /**
     * Escapes a value interpolated into a single-quoted SQL string literal by
     * doubling every single quote.
     */
    @Override
    public String escapeString(String str) {
        return str == null ? null : StringUtils.replace(str, "'", "''");
    }

    @Override
    public String convertIdentifierCase(String identifier) {
        return StringUtils.isBlank(identifier) ? identifier : identifier.toUpperCase(Locale.ROOT);
    }

    /**
     * Escapes identifier content for a position already surrounded by double
     * quotes: every embedded double quote is doubled.
     */
    public static String escapeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        return StringUtils.replace(identifier, "\"", "\"\"");
    }
}

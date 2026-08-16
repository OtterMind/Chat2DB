package ai.chat2db.plugin.hive.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Hive dialect identifier processor. The SPI-facing {@link #quoteIdentifier(String)}
 * is conditional: identifiers that are already valid plain identifiers (and not
 * reserved keywords) are returned unquoted so completion/matching consumers keep
 * working; anything else is wrapped in backticks. DDL-generation call sites that
 * historically always quoted use the SPI always-quote variant
 * {@link #quoteIdentifierAlways(String)}, which
 * doubles every embedded backtick without changing the raw identifier text.
 * String literals are escaped by doubling backslashes then single quotes (Hive
 * treats backslash as an escape character). Shared stateless instance available
 * via {@link #INSTANCE} for call sites without MetaData access.
 */
public class HiveIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    public static final HiveIdentifierProcessor INSTANCE = new HiveIdentifierProcessor();

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "ALL", "ALTER", "AND", "ARRAY", "AS", "AUTHORIZATION", "BETWEEN", "BIGINT", "BINARY",
            "BOOLEAN", "BOTH", "BY", "CASE", "CAST", "CHAR", "COLUMN", "CONF", "CREATE", "CROSS",
            "CUBE", "CURRENT", "CURRENT_DATE", "CURRENT_TIMESTAMP", "CURSOR", "DATABASE", "DATE",
            "DECIMAL", "DELETE", "DESCRIBE", "DISTINCT", "DOUBLE", "DROP", "ELSE", "END", "EXCHANGE",
            "EXISTS", "EXTENDED", "EXTERNAL", "FALSE", "FETCH", "FLOAT", "FOLLOWING", "FOR", "FROM",
            "FULL", "FUNCTION", "GRANT", "GROUP", "GROUPING", "HAVING", "IF", "IMPORT", "IN", "INNER",
            "INSERT", "INT", "INTERSECT", "INTERVAL", "INTO", "IS", "JOIN", "LATERAL", "LEFT", "LESS",
            "LIKE", "LOCAL", "MACRO", "MAP", "MORE", "NONE", "NOT", "NULL", "OF", "ON", "OR", "ORDER",
            "OUT", "OUTER", "OVER", "PARTIALSCAN", "PARTITION", "PERCENT", "PRECEDING", "PRESERVE",
            "PROCEDURE", "RANGE", "READS", "REDUCE", "REVOKE", "RIGHT", "RLIKE", "ROLLUP", "ROW", "ROWS", "SELECT",
            "SET", "SMALLINT", "TABLE", "TABLESAMPLE", "THEN", "TIMESTAMP", "TO", "TRANSFORM", "TRIGGER",
            "TRUE", "TRUNCATE", "UNBOUNDED", "UNION", "UNIQUEJOIN", "UPDATE", "USER", "USING", "UTC_TMESTAMP", "VALUES",
            "VARCHAR", "WHEN", "WHERE", "WINDOW", "WITH");

    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return identifier != null && RESERVED_KEYWORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    /**
     * Conditional quoting for SPI/completion paths: null/blank pass through;
     * valid plain identifiers that are not reserved keywords are returned
     * unquoted; everything else is backtick-quoted like {@link #quoteIdentifierAlways}.
     */
    @Override
    public String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        if (isValidIdentifier(identifier) && !isReservedKeyword(identifier, null, null)) {
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
        return quoteIdentifier(identifier);
    }

    /**
     * Unconditional backtick quoting for DDL-generation paths. Boundary
     * backticks are raw identifier content and are doubled like embedded ones.
     */
    @Override
    public String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "`" + escapeIdentifierContent(identifier) + "`";
    }

    /**
     * Escapes a value interpolated into a single-quoted SQL string literal by
     * doubling backslashes first, then single quotes (Hive treats backslash as
     * an escape character).
     */
    @Override
    public String escapeString(String str) {
        if (str == null) {
            return null;
        }
        return str.replace("\\", "\\\\").replace("'", "''");
    }

    @Override
    public String removeIdentifierQuote(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        if (identifier.startsWith("`") && identifier.endsWith("`") && identifier.length() >= 2) {
            return identifier.substring(1, identifier.length() - 1).replace("``", "`");
        }
        if (identifier.startsWith("\"") && identifier.endsWith("\"") && identifier.length() >= 2) {
            return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
        }
        return identifier;
    }

    @Override
    public boolean isQuoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier) || identifier.length() < 2) {
            return false;
        }
        if (identifier.startsWith("`") && identifier.endsWith("`")) {
            return true;
        }
        return identifier.startsWith("\"") && identifier.endsWith("\"");
    }

    private static String escapeIdentifierContent(String identifier) {
        return identifier == null ? null : StringUtils.replace(identifier, "`", "``");
    }

    /**
     * Escapes identifier content for a position already surrounded by backticks.
     */
    public static String escapeIdentifier(String identifier) {
        return escapeIdentifierContent(identifier);
    }
}

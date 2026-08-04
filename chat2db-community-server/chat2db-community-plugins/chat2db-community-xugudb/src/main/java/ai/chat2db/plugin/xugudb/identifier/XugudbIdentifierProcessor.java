package ai.chat2db.plugin.xugudb.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * XUGUDB dialect identifier processor: double-quoted identifiers with embedded-quote
 * doubling, and single-quote doubling for string literals. Shared stateless
 * instance available via {@link #INSTANCE} for call sites without MetaData access.
 */
public class XugudbIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    public static final XugudbIdentifierProcessor INSTANCE = new XugudbIdentifierProcessor();

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BETWEEN", "BY", "CASE", "CHECK",
            "COLUMN", "CONNECT", "CREATE", "CURRENT", "DATABASE", "DATE", "DEFAULT", "DELETE",
            "DESC", "DISTINCT", "DROP", "ELSE", "END", "EXISTS", "FALSE", "FOR", "FROM",
            "GRANT", "GROUP", "HAVING", "IN", "INDEX", "INNER", "INSERT", "INTERSECT", "INTO",
            "IS", "JOIN", "LEFT", "LIKE", "LIMIT", "NOT", "NULL", "OFFSET", "ON", "OR",
            "ORDER", "OUTER", "PRIMARY", "PROCEDURE", "REFERENCES", "RIGHT", "ROW", "SCHEMA",
            "SELECT", "SET", "TABLE", "THEN", "TO", "TRIGGER", "TRUE", "UNION", "UNIQUE",
            "UPDATE", "USER", "VALUES", "VIEW", "WHEN", "WHERE", "WITH");

    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return identifier != null && RESERVED_KEYWORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    /**
     * SPI-facing conditional quoting: null/blank pass through unchanged; valid plain
     * identifiers stay unquoted (completion and matching paths rely on this); anything
     * else is double-quoted with one surrounding pair stripped and embedded quotes doubled.
     */
    @Override
    public String quoteIdentifier(String identifier) {
        if (identifier == null || StringUtils.isBlank(identifier)) {
            return identifier;
        }
        if (isValidQuotedIdentifier(identifier)) {
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
     * Unconditional quoting for DDL-generation call sites: null passes through,
     * anything else is wrapped in double quotes with doubling.
     */
    @Override
    public String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "\"" + escapeIdentifierContent(identifier) + "\"";
    }

    /**
     * Escapes a value interpolated into a single-quoted SQL string literal by
     * doubling every single quote.
     */
    @Override
    public String escapeString(String str) {
        return str == null ? null : StringUtils.replace(str, "'", "''");
    }

    public String quoteStringLiteral(String str) {
        return str == null ? null : "'" + escapeString(str) + "'";
    }

    private static String escapeIdentifierContent(String identifier) {
        return identifier == null ? null : StringUtils.replace(identifier, "\"", "\"\"");
    }

    /**
     * Escapes identifier content for a position already surrounded by double
     * quotes: strips one surrounding quote pair, then doubles every embedded
     * double quote.
     */
    public static String escapeIdentifier(String identifier) {
        return escapeIdentifierContent(identifier);
    }

    private static boolean isValidQuotedIdentifier(String identifier) {
        if (identifier.length() < 2 || identifier.charAt(0) != '"'
                || identifier.charAt(identifier.length() - 1) != '"') {
            return false;
        }
        for (int i = 1; i < identifier.length() - 1; i++) {
            if (identifier.charAt(i) == '"') {
                if (i + 1 >= identifier.length() - 1 || identifier.charAt(i + 1) != '"') {
                    return false;
                }
                i++;
            }
        }
        return true;
    }
}

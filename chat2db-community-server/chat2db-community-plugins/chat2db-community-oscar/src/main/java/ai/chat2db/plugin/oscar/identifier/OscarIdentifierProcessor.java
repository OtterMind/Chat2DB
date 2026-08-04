package ai.chat2db.plugin.oscar.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Oscar dialect identifier processor: double-quoted identifiers with embedded-quote
 * doubling, and single-quote doubling for string literals. Shared stateless
 * instance available via {@link #INSTANCE} for call sites without MetaData access.
 */
public class OscarIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    public static final OscarIdentifierProcessor INSTANCE = new OscarIdentifierProcessor();

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BEGIN", "BETWEEN", "BY", "CHAR", "CHECK",
            "COLUMN", "COMMENT", "CONNECT", "CONSTRAINT", "CREATE", "CURRENT", "DATE", "DECIMAL", "DEFAULT",
            "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "END", "EXISTS", "FLOAT", "FOR", "FROM", "FUNCTION",
            "GRANT", "GROUP", "HAVING", "IN", "INDEX", "INSERT", "INT", "INTEGER", "INTERSECT", "INTO", "IS",
            "LIKE", "MINUS", "NOT", "NULL", "NUMBER", "ON", "OR", "ORDER", "PRIMARY", "PROCEDURE", "PUBLIC",
            "RETURN", "REVOKE", "ROWNUM", "SELECT", "SET", "SMALLINT", "SYSDATE", "TABLE", "THEN", "TO",
            "TRIGGER", "UNION", "UNIQUE", "UPDATE", "USER", "VALUES", "VARCHAR", "VIEW", "WHERE", "WITH"
    );

    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return identifier != null && RESERVED_KEYWORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    @Override
    public String quoteIdentifier(String identifier, Integer majorVersion, Integer minorVersion) {
        return quoteIdentifier(identifier);
    }

    /**
     * SPI-facing conditional quoting: {@code null} passes through and blank
     * returns unchanged; a valid plain identifier that is not a reserved keyword
     * returns unquoted; anything else is wrapped via {@link #quoteIdentifierAlways}.
     */
    @Override
    public String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        if (isValidQuotedIdentifier(identifier)) {
            return identifier;
        }
        if (isValidIdentifier(identifier)
                && !isReservedKeyword(identifier.toUpperCase(), null, null)) {
            return identifier;
        }
        return quoteIdentifierAlways(identifier);
    }

    /**
     * Always-quote variant for DDL-generation paths: {@code null} passes through,
     * everything else is treated as raw identifier content and wrapped in double
     * quotes after doubling every embedded double quote.
     */
    public String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "\"" + escapeIdentifier(identifier) + "\"";
    }

    /**
     * Always-quote SPI variant that preserves case (used by DDL-generation paths).
     */
    @Override
    public String quoteIdentifierIgnoreCase(String identifier) {
        return quoteIdentifierAlways(identifier);
    }

    @Override
    public String convertIdentifierCase(String identifier) {
        if (StringUtils.isBlank(identifier) || isQuoteIdentifier(identifier)) {
            return identifier;
        }
        return identifier.toUpperCase();
    }

    /**
     * Escapes a value interpolated into a single-quoted SQL string literal by
     * doubling every single quote (surrounding quotes NOT added).
     */
    @Override
    public String escapeString(String str) {
        if (str == null) {
            return null;
        }
        return StringUtils.replace(str, "'", "''");
    }

    /**
     * Escapes raw identifier content for a position already surrounded by double
     * quotes by doubling every embedded double quote.
     */
    public static String escapeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        return StringUtils.replace(identifier, "\"", "\"\"");
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

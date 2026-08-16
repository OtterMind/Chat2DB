package ai.chat2db.plugin.sundb.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * SUNDB dialect identifier processor: double-quoted identifiers with embedded-quote
 * doubling, and single-quote doubling for string literals. Shared stateless
 * instance available via {@link #INSTANCE} for call sites without MetaData access.
 *
 * <p>{@link #quoteIdentifier(String)} follows the SPI conditional contract:
 * valid plain identifiers pass through unquoted and anything else is wrapped in
 * double quotes. {@link #quoteIdentifierAlways(String)} is the unconditional
 * variant reserved for DDL-generation paths that historically always quoted.
 */
public class SUNDBIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    public static final SUNDBIdentifierProcessor INSTANCE = new SUNDBIdentifierProcessor();

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "ACCESS", "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "AUTHORIZATION",
            "BETWEEN", "BY", "CASE", "CAST", "CHECK", "COLUMN", "COMMENT", "CONNECT", "CONSTRAINT",
            "CREATE", "CURRENT", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER",
            "DATABASE", "DATE", "DEFAULT", "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "END",
            "EXISTS", "FALSE", "FOR", "FOREIGN", "FROM", "GRANT", "GROUP", "HAVING", "IN",
            "INDEX", "INSERT", "INTERSECT", "INTERVAL", "INTO", "IS", "JOIN", "LIKE", "LIMIT", "NOT",
            "NULL", "OFFSET", "ON", "OR", "ORDER", "PRIMARY", "PROCEDURE", "REFERENCES", "REVOKE", "ROW", "SCHEMA",
            "SELECT", "SESSION_USER", "SET", "SYSDATE", "TABLE", "THEN", "TO", "TRIGGER", "TRUE",
            "UNION", "UNIQUE", "UPDATE", "USER", "VALUES", "VIEW", "WHEN", "WHERE", "WITH");

    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return identifier != null && RESERVED_KEYWORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    /**
     * SPI conditional quoting: {@code null} passes through, blank is returned
     * unchanged, valid plain identifiers that are not reserved keywords are
     * returned unquoted, and everything else is wrapped with double quotes.
     */
    @Override
    public String quoteIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        if (isValidQuotedIdentifier(identifier)) {
            return identifier;
        }
        // SUNDB folds unquoted identifiers to uppercase, so lowercase names must stay quoted.
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

    /**
     * Conditional quote variant that ignores SUNDB's uppercase folding while
     * still quoting reserved words and invalid identifiers.
     */
    @Override
    public String quoteIdentifierIgnoreCase(String identifier) {
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

    /**
     * Unconditional quoting for DDL-generation call sites: {@code null} passes
     * through, otherwise every double quote is treated as raw identifier content,
     * doubled, and wrapped in double quotes.
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

    private static String escapeIdentifierContent(String identifier) {
        return identifier == null ? null : StringUtils.replace(identifier, "\"", "\"\"");
    }

    /**
     * Escapes identifier content for a position already surrounded by double
     * quotes by doubling every embedded double quote.
     */
    public static String escapeIdentifier(String identifier) {
        return escapeIdentifierContent(identifier);
    }

    @Override
    public String convertIdentifierCase(String identifier) {
        return StringUtils.isBlank(identifier) ? identifier : identifier.toUpperCase(Locale.ROOT);
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

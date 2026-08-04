package ai.chat2db.plugin.postgresql.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * PostgreSQL dialect identifier processor: double-quoted identifiers with embedded-quote
 * doubling, and single-quote doubling for string literals
 * (standard_conforming_strings=on, so backslash is not an escape character).
 * Shared stateless instance available via {@link #INSTANCE} for call sites without MetaData access.
 */
public class PostgreSQLIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    public static final PostgreSQLIdentifierProcessor INSTANCE = new PostgreSQLIdentifierProcessor();

    private static final Set<String> PGSQL_RESERVED_KEYWORDS = new HashSet<>();

    static {
        PGSQL_RESERVED_KEYWORDS.add("ALL");
        PGSQL_RESERVED_KEYWORDS.add("ANALYSE");
        PGSQL_RESERVED_KEYWORDS.add("ANALYZE");
        PGSQL_RESERVED_KEYWORDS.add("AND");
        PGSQL_RESERVED_KEYWORDS.add("ANY");
        PGSQL_RESERVED_KEYWORDS.add("ARRAY");
        PGSQL_RESERVED_KEYWORDS.add("AS");
        PGSQL_RESERVED_KEYWORDS.add("ASC");
        PGSQL_RESERVED_KEYWORDS.add("ASYMMETRIC");
        PGSQL_RESERVED_KEYWORDS.add("BOTH");
        PGSQL_RESERVED_KEYWORDS.add("CASE");
        PGSQL_RESERVED_KEYWORDS.add("CAST");
        PGSQL_RESERVED_KEYWORDS.add("CHECK");
        PGSQL_RESERVED_KEYWORDS.add("COLLATE");
        PGSQL_RESERVED_KEYWORDS.add("COLUMN");
        PGSQL_RESERVED_KEYWORDS.add("CONSTRAINT");
        PGSQL_RESERVED_KEYWORDS.add("CREATE");
        PGSQL_RESERVED_KEYWORDS.add("CURRENT_CATALOG");
        PGSQL_RESERVED_KEYWORDS.add("CURRENT_DATE");
        PGSQL_RESERVED_KEYWORDS.add("CURRENT_ROLE");
        PGSQL_RESERVED_KEYWORDS.add("CURRENT_TIME");
        PGSQL_RESERVED_KEYWORDS.add("CURRENT_TIMESTAMP");
        PGSQL_RESERVED_KEYWORDS.add("CURRENT_USER");
        PGSQL_RESERVED_KEYWORDS.add("DEFAULT");
        PGSQL_RESERVED_KEYWORDS.add("DEFERRABLE");
        PGSQL_RESERVED_KEYWORDS.add("DESC");
        PGSQL_RESERVED_KEYWORDS.add("DISTINCT");
        PGSQL_RESERVED_KEYWORDS.add("DO");
        PGSQL_RESERVED_KEYWORDS.add("ELSE");
        PGSQL_RESERVED_KEYWORDS.add("END");
        PGSQL_RESERVED_KEYWORDS.add("EXCEPT");
        PGSQL_RESERVED_KEYWORDS.add("FALSE");
        PGSQL_RESERVED_KEYWORDS.add("FETCH");
        PGSQL_RESERVED_KEYWORDS.add("FOR");
        PGSQL_RESERVED_KEYWORDS.add("FOREIGN");
        PGSQL_RESERVED_KEYWORDS.add("FROM");
        PGSQL_RESERVED_KEYWORDS.add("GRANT");
        PGSQL_RESERVED_KEYWORDS.add("GROUP");
        PGSQL_RESERVED_KEYWORDS.add("HAVING");
        PGSQL_RESERVED_KEYWORDS.add("IN");
        PGSQL_RESERVED_KEYWORDS.add("INITIALLY");
        PGSQL_RESERVED_KEYWORDS.add("INTERSECT");
        PGSQL_RESERVED_KEYWORDS.add("INTO");
        PGSQL_RESERVED_KEYWORDS.add("LATERAL");
        PGSQL_RESERVED_KEYWORDS.add("LEADING");
        PGSQL_RESERVED_KEYWORDS.add("LIMIT");
        PGSQL_RESERVED_KEYWORDS.add("LOCALTIME");
        PGSQL_RESERVED_KEYWORDS.add("LOCALTIMESTAMP");
        PGSQL_RESERVED_KEYWORDS.add("NOT");
        PGSQL_RESERVED_KEYWORDS.add("NULL");
        PGSQL_RESERVED_KEYWORDS.add("OFFSET");
        PGSQL_RESERVED_KEYWORDS.add("ON");
        PGSQL_RESERVED_KEYWORDS.add("ONLY");
        PGSQL_RESERVED_KEYWORDS.add("OR");
        PGSQL_RESERVED_KEYWORDS.add("ORDER");
        PGSQL_RESERVED_KEYWORDS.add("PLACING");
        PGSQL_RESERVED_KEYWORDS.add("PRIMARY");
        PGSQL_RESERVED_KEYWORDS.add("REFERENCES");
        PGSQL_RESERVED_KEYWORDS.add("RETURNING");
        PGSQL_RESERVED_KEYWORDS.add("SELECT");
        PGSQL_RESERVED_KEYWORDS.add("SESSION_USER");
        PGSQL_RESERVED_KEYWORDS.add("SOME");
        PGSQL_RESERVED_KEYWORDS.add("SYMMETRIC");
        PGSQL_RESERVED_KEYWORDS.add("TABLE");
        PGSQL_RESERVED_KEYWORDS.add("THEN");
        PGSQL_RESERVED_KEYWORDS.add("TO");
        PGSQL_RESERVED_KEYWORDS.add("TRAILING");
        PGSQL_RESERVED_KEYWORDS.add("TRUE");
        PGSQL_RESERVED_KEYWORDS.add("UNION");
        PGSQL_RESERVED_KEYWORDS.add("UNIQUE");
        PGSQL_RESERVED_KEYWORDS.add("USER");
        PGSQL_RESERVED_KEYWORDS.add("USING");
        PGSQL_RESERVED_KEYWORDS.add("VARIADIC");
        PGSQL_RESERVED_KEYWORDS.add("WHEN");
        PGSQL_RESERVED_KEYWORDS.add("WHERE");
        PGSQL_RESERVED_KEYWORDS.add("WINDOW");
        PGSQL_RESERVED_KEYWORDS.add("WITH");
    }


    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return identifier != null && PGSQL_RESERVED_KEYWORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    /**
     * SPI-facing conditional quoting: {@code null} stays {@code null}, blank is returned
     * unchanged, a valid plain identifier that is not a reserved keyword is returned
     * unquoted, and anything else is wrapped in double quotes with one surrounding
     * double quotes doubled as raw identifier content.
     */
    @Override
    public String quoteIdentifier(String identifier, Integer majorVersion, Integer minorVersion) {
        return quoteIdentifier(identifier);
    }


    @Override
    public String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        // PostgreSQL folds unquoted identifiers to lowercase, so mixed-case names must stay quoted.
        if (isValidIdentifier(identifier) && !containsUpperCase(identifier)
                && !isReservedKeyword(identifier, null, null)) {
            return identifier;
        }
        return quoteIdentifierAlways(identifier);
    }

    /**
     * Conditional quote variant that preserves the original identifier case.
     */
    @Override
    public String quoteIdentifierIgnoreCase(String identifier) {
        return quoteIdentifier(identifier);
    }

    /**
     * Unconditionally wraps with double quotes and doubles every embedded double quote.
     * For DDL-generation call sites that must always emit quoted identifiers. Returns
     * {@code null} for {@code null}.
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
     * doubling every single quote. Returns {@code null} for {@code null}.
     */
    @Override
    public String escapeString(String str) {
        return str == null ? null : StringUtils.replace(str, "'", "''");
    }

    private static String escapeIdentifierContent(String identifier) {
        if (identifier == null) {
            return null;
        }
        return StringUtils.replace(identifier, "\"", "\"\"");
    }

    /**
     * Escapes identifier content for a position already surrounded by double
     * quotes by doubling every embedded double quote. Returns {@code null} for
     * {@code null}.
     */
    public static String escapeIdentifier(String identifier) {
        return escapeIdentifierContent(identifier);
    }

    @Override
    public String convertIdentifierCase(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        } else {
            return identifier.toLowerCase(Locale.ROOT);
        }
    }
}

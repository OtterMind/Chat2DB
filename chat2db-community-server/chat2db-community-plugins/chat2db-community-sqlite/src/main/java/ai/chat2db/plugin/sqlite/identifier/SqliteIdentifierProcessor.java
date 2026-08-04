package ai.chat2db.plugin.sqlite.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * SQLite dialect identifier processor: double-quoted identifiers with embedded-quote
 * doubling, and single-quote doubling for string literals. Identifiers that are
 * already valid pass through unquoted. Shared stateless instance available via
 * {@link #INSTANCE} for call sites without MetaData access.
 */
public class SqliteIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    public static final SqliteIdentifierProcessor INSTANCE = new SqliteIdentifierProcessor();

    private static final Set<String> RESERVED_KEYWORDS = Set.copyOf(Arrays.asList((
            "ABORT ACTION ADD AFTER ALL ALTER ANALYZE AND AS ASC ATTACH AUTOINCREMENT BEFORE BEGIN "
                    + "BETWEEN BY CASCADE CASE CAST CHECK COLLATE COLUMN COMMIT CONFLICT CONSTRAINT CREATE CROSS "
                    + "CURRENT CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP DATABASE DEFAULT DEFERRABLE DEFERRED "
                    + "DELETE DESC DETACH DISTINCT DO DROP EACH ELSE END ESCAPE EXCEPT EXCLUDE EXCLUSIVE EXISTS "
                    + "EXPLAIN FAIL FILTER FIRST FOLLOWING FOR FOREIGN FROM FULL GENERATED GLOB GROUP GROUPS "
                    + "HAVING IF IGNORE IMMEDIATE IN INDEX INDEXED INITIALLY INNER INSERT INSTEAD INTERSECT INTO "
                    + "IS ISNULL JOIN KEY LAST LEFT LIKE LIMIT MATCH MATERIALIZED NATURAL NO NOT NOTHING NOTNULL "
                    + "NULL NULLS OF OFFSET ON OR ORDER OTHERS OUTER OVER PARTITION PLAN PRAGMA PRECEDING PRIMARY "
                    + "QUERY RAISE RANGE RECURSIVE REFERENCES REGEXP REINDEX RELEASE RENAME REPLACE RESTRICT "
                    + "RETURNING RIGHT ROLLBACK ROW ROWS SAVEPOINT SELECT SET TABLE TEMP TEMPORARY THEN TIES TO "
                    + "TRANSACTION TRIGGER UNBOUNDED UNION UNIQUE UPDATE USING VACUUM VALUES VIEW VIRTUAL WHEN "
                    + "WHERE WINDOW WITH WITHOUT").split("\\s+")));

    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return identifier != null && RESERVED_KEYWORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    /**
     * Valid non-keyword identifiers pass through unchanged; already valid quoted
     * identifiers are preserved; anything else is quoted safely.
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

    @Override
    public String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "\"" + escapeIdentifierContent(identifier) + "\"";
    }

    /**
     * Escapes a value interpolated into a single-quoted SQL string literal by
     * doubling every single quote. Preserves {@code null}.
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
     * quotes by doubling every embedded double quote. Preserves {@code null}.
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

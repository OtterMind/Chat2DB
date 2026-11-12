package ai.chat2db.spi.util;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Lexical rules that decide which parts of a SQL text are string literals,
 * quoted identifiers or comments, so that parameter placeholders inside them
 * are ignored.
 * <p>
 * The database-type mapping mirrors {@code sqlParameterSyntaxFor} in the client
 * ({@code chat2db-community-client/src/utils/sqlParameters.ts}); keep both in
 * step so the prompt and the backend binding agree on the placeholders.
 *
 * @param backslashEscapes           {@code \} escapes the next character inside quotes (MySQL family).
 * @param hashLineComments           {@code #} starts a line comment (MySQL family, BigQuery).
 * @param dollarQuotedStrings        {@code $tag$...$tag$} and {@code E'...'} strings (PostgreSQL family).
 * @param nestedBlockComments        block comments nest (PostgreSQL family).
 * @param doubledQuestionMarkEscape  {@code ??} is a literal question mark for the JDBC driver (PgJDBC).
 * @param bracketIdentifiers         {@code [name]} is a quoted identifier (SQL Server).
 * @param alternativeQuotedStrings   {@code q'[...]'} strings (Oracle family).
 * @param questionMarkOperators      {@code ?|} and {@code ?&} are operators, never placeholders (PostgreSQL family).
 */
public record SqlParameterSyntax(boolean backslashEscapes, boolean hashLineComments, boolean dollarQuotedStrings,
                                 boolean nestedBlockComments, boolean doubledQuestionMarkEscape,
                                 boolean bracketIdentifiers, boolean alternativeQuotedStrings,
                                 boolean questionMarkOperators) {

    public static final SqlParameterSyntax ANSI =
            new SqlParameterSyntax(false, false, false, false, false, false, false, false);

    public static final SqlParameterSyntax MYSQL =
            new SqlParameterSyntax(true, true, false, false, false, false, false, false);

    public static final SqlParameterSyntax BACKSLASH_ESCAPES =
            new SqlParameterSyntax(true, false, false, false, false, false, false, false);

    public static final SqlParameterSyntax POSTGRESQL =
            new SqlParameterSyntax(false, false, true, true, true, false, false, true);

    public static final SqlParameterSyntax SQLSERVER =
            new SqlParameterSyntax(false, false, false, false, false, true, false, false);

    public static final SqlParameterSyntax ORACLE =
            new SqlParameterSyntax(false, false, false, false, false, false, true, false);

    // BigQuery shares the MySQL lexical rules: backslash escapes and # comments.
    private static final Set<String> MYSQL_TYPES =
            Set.of("MYSQL", "MARIADB", "OCEANBASE", "TIDB", "DORIS", "STARROCKS", "BIGQUERY");

    private static final Set<String> BACKSLASH_ESCAPE_TYPES = Set.of("CLICKHOUSE", "HIVE");

    private static final Set<String> POSTGRESQL_TYPES =
            Set.of("POSTGRESQL", "KINGBASE", "OPENGAUSS", "GAUSSDB", "COCKROACHDB", "REDSHIFT");

    private static final Set<String> ORACLE_TYPES = Set.of("ORACLE", "OCEANBASE_ORACLE", "DM", "SUNDB");

    private static final Set<String> UNSUPPORTED_TYPES = Set.of("MONGODB", "REDIS");

    /**
     * Returns the lexical rules for a Chat2DB database type code.
     *
     * @param databaseType database type code such as {@code MYSQL}.
     * @return the rules, or {@code null} when the database does not execute SQL text
     * and therefore cannot bind parameters.
     */
    public static SqlParameterSyntax forDatabaseType(String databaseType) {
        String type = StringUtils.upperCase(StringUtils.trimToEmpty(databaseType), Locale.ROOT);
        if (UNSUPPORTED_TYPES.contains(type)) {
            return null;
        }
        if (MYSQL_TYPES.contains(type)) {
            return MYSQL;
        }
        if (BACKSLASH_ESCAPE_TYPES.contains(type)) {
            return BACKSLASH_ESCAPES;
        }
        if (POSTGRESQL_TYPES.contains(type)) {
            return POSTGRESQL;
        }
        if ("SQLSERVER".equals(type)) {
            return SQLSERVER;
        }
        if (ORACLE_TYPES.contains(type)) {
            return ORACLE;
        }
        return ANSI;
    }
}

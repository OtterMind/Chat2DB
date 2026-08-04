package ai.chat2db.plugin.sqlserver.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static ai.chat2db.plugin.sqlserver.constant.SqlServerIdentifierProcessorConstants.*;

/**
 * SQL Server dialect identifier processor: bracket-quoted identifiers with
 * embedded {@code ]} doubling, and single-quote doubling for string literals
 * (SQL Server does not treat backslash as an escape character, so backslashes
 * are never doubled). Shared stateless instance available via {@link #INSTANCE}
 * for call sites without MetaData access.
 */
public class SqlServerIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    public static final SqlServerIdentifierProcessor INSTANCE = new SqlServerIdentifierProcessor();

    public static final Set<String> SQL_SERVER_RESERVED_KEYWORDS = new HashSet<>();

    static {
        SQL_SERVER_RESERVED_KEYWORDS.add("ADD");
        SQL_SERVER_RESERVED_KEYWORDS.add("EXTERNAL");
        SQL_SERVER_RESERVED_KEYWORDS.add("PROCEDURE");
        SQL_SERVER_RESERVED_KEYWORDS.add("ALL");
        SQL_SERVER_RESERVED_KEYWORDS.add("FETCH");
        SQL_SERVER_RESERVED_KEYWORDS.add("PUBLIC");
        SQL_SERVER_RESERVED_KEYWORDS.add("ALTER");
        SQL_SERVER_RESERVED_KEYWORDS.add("FILE");
        SQL_SERVER_RESERVED_KEYWORDS.add("RAISERROR");
        SQL_SERVER_RESERVED_KEYWORDS.add("AND");
        SQL_SERVER_RESERVED_KEYWORDS.add("FILLFACTOR");
        SQL_SERVER_RESERVED_KEYWORDS.add("READ");
        SQL_SERVER_RESERVED_KEYWORDS.add("ANY");
        SQL_SERVER_RESERVED_KEYWORDS.add("FOR");
        SQL_SERVER_RESERVED_KEYWORDS.add("READTEXT");
        SQL_SERVER_RESERVED_KEYWORDS.add("AS");
        SQL_SERVER_RESERVED_KEYWORDS.add("FOREIGN");
        SQL_SERVER_RESERVED_KEYWORDS.add("RECONFIGURE");
        SQL_SERVER_RESERVED_KEYWORDS.add("ASC");
        SQL_SERVER_RESERVED_KEYWORDS.add("FREETEXT");
        SQL_SERVER_RESERVED_KEYWORDS.add("REFERENCES");
        SQL_SERVER_RESERVED_KEYWORDS.add("AUTHORIZATION");
        SQL_SERVER_RESERVED_KEYWORDS.add("FREETEXTTABLE");
        SQL_SERVER_RESERVED_KEYWORDS.add("BACKUP");
        SQL_SERVER_RESERVED_KEYWORDS.add("FROM");
        SQL_SERVER_RESERVED_KEYWORDS.add("RESTORE");
        SQL_SERVER_RESERVED_KEYWORDS.add("BEGIN");
        SQL_SERVER_RESERVED_KEYWORDS.add("FULL");
        SQL_SERVER_RESERVED_KEYWORDS.add("RESTRICT");
        SQL_SERVER_RESERVED_KEYWORDS.add("BETWEEN");
        SQL_SERVER_RESERVED_KEYWORDS.add("FUNCTION");
        SQL_SERVER_RESERVED_KEYWORDS.add("RETURN");
        SQL_SERVER_RESERVED_KEYWORDS.add("BREAK");
        SQL_SERVER_RESERVED_KEYWORDS.add("GOTO");
        SQL_SERVER_RESERVED_KEYWORDS.add("REVERT");
        SQL_SERVER_RESERVED_KEYWORDS.add("BROWSE");
        SQL_SERVER_RESERVED_KEYWORDS.add("GRANT");
        SQL_SERVER_RESERVED_KEYWORDS.add("REVOKE");
        SQL_SERVER_RESERVED_KEYWORDS.add("BULK");
        SQL_SERVER_RESERVED_KEYWORDS.add("GROUP");
        SQL_SERVER_RESERVED_KEYWORDS.add("RIGHT");
        SQL_SERVER_RESERVED_KEYWORDS.add("BY");
        SQL_SERVER_RESERVED_KEYWORDS.add("HAVING");
        SQL_SERVER_RESERVED_KEYWORDS.add("ROLLBACK");
        SQL_SERVER_RESERVED_KEYWORDS.add("CASCADE");
        SQL_SERVER_RESERVED_KEYWORDS.add("HOLDLOCK");
        SQL_SERVER_RESERVED_KEYWORDS.add("ROWCOUNT");
        SQL_SERVER_RESERVED_KEYWORDS.add("CASE");
        SQL_SERVER_RESERVED_KEYWORDS.add("IDENTITY");
        SQL_SERVER_RESERVED_KEYWORDS.add("ROWGUIDCOL");
        SQL_SERVER_RESERVED_KEYWORDS.add("CHECK");
        SQL_SERVER_RESERVED_KEYWORDS.add("IDENTITY_INSERT");
        SQL_SERVER_RESERVED_KEYWORDS.add("RULE");
        SQL_SERVER_RESERVED_KEYWORDS.add("CHECKPOINT");
        SQL_SERVER_RESERVED_KEYWORDS.add("IDENTITYCOL");
        SQL_SERVER_RESERVED_KEYWORDS.add("SAVE");
        SQL_SERVER_RESERVED_KEYWORDS.add("CLOSE");
        SQL_SERVER_RESERVED_KEYWORDS.add("IF");
        SQL_SERVER_RESERVED_KEYWORDS.add("SCHEMA");
        SQL_SERVER_RESERVED_KEYWORDS.add("CLUSTERED");
        SQL_SERVER_RESERVED_KEYWORDS.add("IN");
        SQL_SERVER_RESERVED_KEYWORDS.add("SECURITYAUDIT");
        SQL_SERVER_RESERVED_KEYWORDS.add("COALESCE");
        SQL_SERVER_RESERVED_KEYWORDS.add("INDEX");
        SQL_SERVER_RESERVED_KEYWORDS.add("SELECT");
        SQL_SERVER_RESERVED_KEYWORDS.add("COLLATE");
        SQL_SERVER_RESERVED_KEYWORDS.add("INNER");
        SQL_SERVER_RESERVED_KEYWORDS.add("SEMANTICKEYPHRASETABLE");
        SQL_SERVER_RESERVED_KEYWORDS.add("COLUMN");
        SQL_SERVER_RESERVED_KEYWORDS.add("INSERT");
        SQL_SERVER_RESERVED_KEYWORDS.add("SEMANTICSIMILARITYDETAILSTABLE");
        SQL_SERVER_RESERVED_KEYWORDS.add("COMMIT");
        SQL_SERVER_RESERVED_KEYWORDS.add("INTERSECT");
        SQL_SERVER_RESERVED_KEYWORDS.add("SEMANTICSIMILARITYTABLE");
        SQL_SERVER_RESERVED_KEYWORDS.add("COMPUTE");
        SQL_SERVER_RESERVED_KEYWORDS.add("INTO");
        SQL_SERVER_RESERVED_KEYWORDS.add("SESSION_USER");
        SQL_SERVER_RESERVED_KEYWORDS.add("CONSTRAINT");
        SQL_SERVER_RESERVED_KEYWORDS.add("IS");
        SQL_SERVER_RESERVED_KEYWORDS.add("SET");
        SQL_SERVER_RESERVED_KEYWORDS.add("CONTAINS");
        SQL_SERVER_RESERVED_KEYWORDS.add("JOIN");
        SQL_SERVER_RESERVED_KEYWORDS.add("SETUSER");
        SQL_SERVER_RESERVED_KEYWORDS.add("CONTAINSTABLE");
        SQL_SERVER_RESERVED_KEYWORDS.add("KEY");
        SQL_SERVER_RESERVED_KEYWORDS.add("SHUTDOWN");
        SQL_SERVER_RESERVED_KEYWORDS.add("CONTINUE");
        SQL_SERVER_RESERVED_KEYWORDS.add("KILL");
        SQL_SERVER_RESERVED_KEYWORDS.add("SOME");
        SQL_SERVER_RESERVED_KEYWORDS.add("CONVERT");
        SQL_SERVER_RESERVED_KEYWORDS.add("LEFT");
        SQL_SERVER_RESERVED_KEYWORDS.add("STATISTICS");
        SQL_SERVER_RESERVED_KEYWORDS.add("CREATE");
        SQL_SERVER_RESERVED_KEYWORDS.add("LIKE");
        SQL_SERVER_RESERVED_KEYWORDS.add("SYSTEM_USER");
        SQL_SERVER_RESERVED_KEYWORDS.add("CROSS");
        SQL_SERVER_RESERVED_KEYWORDS.add("LINENO");
        SQL_SERVER_RESERVED_KEYWORDS.add("TABLE");
        SQL_SERVER_RESERVED_KEYWORDS.add("CURRENT_DATE");
        SQL_SERVER_RESERVED_KEYWORDS.add("MERGE");
        SQL_SERVER_RESERVED_KEYWORDS.add("TEXTSIZE");
        SQL_SERVER_RESERVED_KEYWORDS.add("CURRENT_TIME");
        SQL_SERVER_RESERVED_KEYWORDS.add("NATIONAL");
        SQL_SERVER_RESERVED_KEYWORDS.add("THEN");
        SQL_SERVER_RESERVED_KEYWORDS.add("CURRENT_TIMESTAMP");
        SQL_SERVER_RESERVED_KEYWORDS.add("NOCHECK");
        SQL_SERVER_RESERVED_KEYWORDS.add("TO");
        SQL_SERVER_RESERVED_KEYWORDS.add("CURRENT_USER");
        SQL_SERVER_RESERVED_KEYWORDS.add("NONCLUSTERED");
        SQL_SERVER_RESERVED_KEYWORDS.add("TOP");
        SQL_SERVER_RESERVED_KEYWORDS.add("CURSOR");
        SQL_SERVER_RESERVED_KEYWORDS.add("NOT");
        SQL_SERVER_RESERVED_KEYWORDS.add("TRAN");
        SQL_SERVER_RESERVED_KEYWORDS.add("DATABASE");
        SQL_SERVER_RESERVED_KEYWORDS.add("NULL");
        SQL_SERVER_RESERVED_KEYWORDS.add("TRANSACTION");
        SQL_SERVER_RESERVED_KEYWORDS.add("DBCC");
        SQL_SERVER_RESERVED_KEYWORDS.add("NULLIF");
        SQL_SERVER_RESERVED_KEYWORDS.add("TRIGGER");
        SQL_SERVER_RESERVED_KEYWORDS.add("DEALLOCATE");
        SQL_SERVER_RESERVED_KEYWORDS.add("OF");
        SQL_SERVER_RESERVED_KEYWORDS.add("TRUNCATE");
        SQL_SERVER_RESERVED_KEYWORDS.add("DECLARE");
        SQL_SERVER_RESERVED_KEYWORDS.add("OFF");
        SQL_SERVER_RESERVED_KEYWORDS.add("TRY_CONVERT");
        SQL_SERVER_RESERVED_KEYWORDS.add("DEFAULT");
        SQL_SERVER_RESERVED_KEYWORDS.add("OFFSETS");
        SQL_SERVER_RESERVED_KEYWORDS.add("TSEQUAL");
        SQL_SERVER_RESERVED_KEYWORDS.add("DELETE");
        SQL_SERVER_RESERVED_KEYWORDS.add("ON");
        SQL_SERVER_RESERVED_KEYWORDS.add("UNION");
        SQL_SERVER_RESERVED_KEYWORDS.add("DENY");
        SQL_SERVER_RESERVED_KEYWORDS.add("OPEN");
        SQL_SERVER_RESERVED_KEYWORDS.add("UNIQUE");
        SQL_SERVER_RESERVED_KEYWORDS.add("DESC");
        SQL_SERVER_RESERVED_KEYWORDS.add("OPENDATASOURCE");
        SQL_SERVER_RESERVED_KEYWORDS.add("UNPIVOT");
        SQL_SERVER_RESERVED_KEYWORDS.add("DISK");
        SQL_SERVER_RESERVED_KEYWORDS.add("OPENQUERY");
        SQL_SERVER_RESERVED_KEYWORDS.add("UPDATE");
        SQL_SERVER_RESERVED_KEYWORDS.add("DISTINCT");
        SQL_SERVER_RESERVED_KEYWORDS.add("OPENROWSET");
        SQL_SERVER_RESERVED_KEYWORDS.add("UPDATETEXT");
        SQL_SERVER_RESERVED_KEYWORDS.add("DISTRIBUTED");
        SQL_SERVER_RESERVED_KEYWORDS.add("OPENXML");
        SQL_SERVER_RESERVED_KEYWORDS.add("USE");
        SQL_SERVER_RESERVED_KEYWORDS.add("DOUBLE");
        SQL_SERVER_RESERVED_KEYWORDS.add("OPTION");
        SQL_SERVER_RESERVED_KEYWORDS.add("USER");
        SQL_SERVER_RESERVED_KEYWORDS.add("DROP");
        SQL_SERVER_RESERVED_KEYWORDS.add("OR");
        SQL_SERVER_RESERVED_KEYWORDS.add("VALUES");
        SQL_SERVER_RESERVED_KEYWORDS.add("DUMP");
        SQL_SERVER_RESERVED_KEYWORDS.add("ORDER");
        SQL_SERVER_RESERVED_KEYWORDS.add("VARYING");
        SQL_SERVER_RESERVED_KEYWORDS.add("ELSE");
        SQL_SERVER_RESERVED_KEYWORDS.add("OUTER");
        SQL_SERVER_RESERVED_KEYWORDS.add("VIEW");
        SQL_SERVER_RESERVED_KEYWORDS.add("END");
        SQL_SERVER_RESERVED_KEYWORDS.add("OVER");
        SQL_SERVER_RESERVED_KEYWORDS.add("WAITFOR");
        SQL_SERVER_RESERVED_KEYWORDS.add("ERRLVL");
        SQL_SERVER_RESERVED_KEYWORDS.add("PERCENT");
        SQL_SERVER_RESERVED_KEYWORDS.add("WHEN");
        SQL_SERVER_RESERVED_KEYWORDS.add("ESCAPE");
        SQL_SERVER_RESERVED_KEYWORDS.add("PIVOT");
        SQL_SERVER_RESERVED_KEYWORDS.add("WHERE");
        SQL_SERVER_RESERVED_KEYWORDS.add("EXCEPT");
        SQL_SERVER_RESERVED_KEYWORDS.add("PLAN");
        SQL_SERVER_RESERVED_KEYWORDS.add("WHILE");
        SQL_SERVER_RESERVED_KEYWORDS.add("EXEC");
        SQL_SERVER_RESERVED_KEYWORDS.add("PRECISION");
        SQL_SERVER_RESERVED_KEYWORDS.add("WITH");
        SQL_SERVER_RESERVED_KEYWORDS.add("EXECUTE");
        SQL_SERVER_RESERVED_KEYWORDS.add("PRIMARY");
        SQL_SERVER_RESERVED_KEYWORDS.add("WITHIN GROUP");
        SQL_SERVER_RESERVED_KEYWORDS.add("EXISTS");
        SQL_SERVER_RESERVED_KEYWORDS.add("PRINT");
        SQL_SERVER_RESERVED_KEYWORDS.add("WRITETEXT");
        SQL_SERVER_RESERVED_KEYWORDS.add("EXIT");
        SQL_SERVER_RESERVED_KEYWORDS.add("PROC");
    }


    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return identifier != null && SQL_SERVER_RESERVED_KEYWORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    /**
     * Quotes conditionally per the SPI contract: {@code null} passes through,
     * blank input is returned unchanged, and an identifier that is already a
     * valid plain identifier and not a reserved keyword is returned unquoted.
     * Anything else is wrapped with {@link #quoteIdentifierAlways(String)}.
     */
    @Override
    public String quoteIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
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

    /** Conditional quote variant that preserves the original identifier case. */
    @Override
    public String quoteIdentifierIgnoreCase(String identifier) {
        return quoteIdentifier(identifier);
    }

    /**
     * Unconditionally quotes with square brackets and doubles every closing
     * bracket in the raw identifier. Boundary brackets are data, not existing
     * quote syntax, so this satisfies the SPI round-trip contract.
     */
    @Override
    public String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "[" + escapeIdentifierContent(identifier) + "]";
    }

    /**
     * Escapes a value interpolated into a single-quoted SQL string literal by
     * doubling every single quote. Backslashes are literal in Transact-SQL and
     * are therefore left untouched.
     */
    @Override
    public String escapeString(String str) {
        return str == null ? null : StringUtils.replace(str, "'", "''");
    }

    private static String escapeIdentifierContent(String identifier) {
        if (identifier == null) {
            return "";
        }
        return StringUtils.replace(identifier, "]", "]]");
    }

    /**
     * Escapes raw identifier content for a position already surrounded by
     * square brackets.
     */
    public static String escapeIdentifier(String identifier) {
        return escapeIdentifierContent(identifier);
    }

    @Override
    public String removeIdentifierQuote(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        StringBuilder unquoted = new StringBuilder(identifier.length());
        boolean removedQuote = false;
        int offset = 0;
        while (offset < identifier.length()) {
            char first = identifier.charAt(offset);
            if (first == '[' || first == '"') {
                char closingDelimiter = first == '[' ? ']' : '"';
                StringBuilder part = new StringBuilder();
                boolean closed = false;
                offset++;
                while (offset < identifier.length()) {
                    char current = identifier.charAt(offset);
                    if (current == closingDelimiter) {
                        if (offset + 1 < identifier.length()
                                && identifier.charAt(offset + 1) == closingDelimiter) {
                            part.append(closingDelimiter);
                            offset += 2;
                            continue;
                        }
                        offset++;
                        closed = true;
                        break;
                    }
                    part.append(current);
                    offset++;
                }
                if (!closed || (offset < identifier.length() && identifier.charAt(offset) != '.')) {
                    return identifier;
                }
                unquoted.append(part);
                removedQuote = true;
            } else {
                int partEnd = identifier.indexOf('.', offset);
                if (partEnd < 0) {
                    partEnd = identifier.length();
                }
                String part = identifier.substring(offset, partEnd);
                if (part.indexOf('[') >= 0 || part.indexOf(']') >= 0 || part.indexOf('"') >= 0) {
                    return identifier;
                }
                unquoted.append(part);
                offset = partEnd;
            }

            if (offset < identifier.length()) {
                unquoted.append('.');
                offset++;
                if (offset == identifier.length()) {
                    return identifier;
                }
            }
        }
        return removedQuote ? unquoted.toString() : identifier;
    }

    @Override
    public boolean isQuoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return false;
        }
        return !identifier.equals(removeIdentifierQuote(identifier));
    }
}

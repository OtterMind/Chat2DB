package ai.chat2db.plugin.dm.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class DMIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    /**
     * Shared stateless instance for call sites without MetaData access.
     */
    public static final DMIdentifierProcessor INSTANCE = new DMIdentifierProcessor();

    public static final Set<String> DM_RESERVED_KEYWORDS = new HashSet<>();

    static {
        DM_RESERVED_KEYWORDS.add("KEYWORD");
        DM_RESERVED_KEYWORDS.add("ABSOLUTE");
        DM_RESERVED_KEYWORDS.add("ABSTRACT");
        DM_RESERVED_KEYWORDS.add("ADD");
        DM_RESERVED_KEYWORDS.add("ADMIN");
        DM_RESERVED_KEYWORDS.add("ALL");
        DM_RESERVED_KEYWORDS.add("ALTER");
        DM_RESERVED_KEYWORDS.add("AND");
        DM_RESERVED_KEYWORDS.add("ANY");
        DM_RESERVED_KEYWORDS.add("ARRAY");
        DM_RESERVED_KEYWORDS.add("ARRAYLEN");
        DM_RESERVED_KEYWORDS.add("AS");
        DM_RESERVED_KEYWORDS.add("ASC");
        DM_RESERVED_KEYWORDS.add("ASSIGN");
        DM_RESERVED_KEYWORDS.add("AUDIT");
        DM_RESERVED_KEYWORDS.add("AUTHORIZATION");
        DM_RESERVED_KEYWORDS.add("AUTO_INCREMENT");
        DM_RESERVED_KEYWORDS.add("BEGIN");
        DM_RESERVED_KEYWORDS.add("BETWEEN");
        DM_RESERVED_KEYWORDS.add("BIGDATEDIFF");
        DM_RESERVED_KEYWORDS.add("BINARY");
        DM_RESERVED_KEYWORDS.add("BOOL");
        DM_RESERVED_KEYWORDS.add("BOTH");
        DM_RESERVED_KEYWORDS.add("BREAK");
        DM_RESERVED_KEYWORDS.add("BSTRING");
        DM_RESERVED_KEYWORDS.add("BY");
        DM_RESERVED_KEYWORDS.add("BYTE");
        DM_RESERVED_KEYWORDS.add("CALL");
        DM_RESERVED_KEYWORDS.add("CASE");
        DM_RESERVED_KEYWORDS.add("CAST");
        DM_RESERVED_KEYWORDS.add("CATCH");
        DM_RESERVED_KEYWORDS.add("CHAR");
        DM_RESERVED_KEYWORDS.add("CHECK");
        DM_RESERVED_KEYWORDS.add("CLASS");
        DM_RESERVED_KEYWORDS.add("CLUSTER");
        DM_RESERVED_KEYWORDS.add("CLUSTERBTR");
        DM_RESERVED_KEYWORDS.add("COLLATION");
        DM_RESERVED_KEYWORDS.add("COLUMN");
        DM_RESERVED_KEYWORDS.add("COMMENT");
        DM_RESERVED_KEYWORDS.add("COMMIT");
        DM_RESERVED_KEYWORDS.add("COMMITWORK");
        DM_RESERVED_KEYWORDS.add("CONNECT");
        DM_RESERVED_KEYWORDS.add("CONNECT_BY_ROOT");
        DM_RESERVED_KEYWORDS.add("CONST");
        DM_RESERVED_KEYWORDS.add("CONSTRAINT");
        DM_RESERVED_KEYWORDS.add("CONTAINS");
        DM_RESERVED_KEYWORDS.add("CONTEXT");
        DM_RESERVED_KEYWORDS.add("CONTINUE");
        DM_RESERVED_KEYWORDS.add("CONVERT");
        DM_RESERVED_KEYWORDS.add("CORRESPONDING");
        DM_RESERVED_KEYWORDS.add("CREATE");
        DM_RESERVED_KEYWORDS.add("CROSS");
        DM_RESERVED_KEYWORDS.add("CRYPTO");
        DM_RESERVED_KEYWORDS.add("CUBE");
        DM_RESERVED_KEYWORDS.add("CURRENT");
        DM_RESERVED_KEYWORDS.add("CURSOR");
        DM_RESERVED_KEYWORDS.add("DATEADD");
        DM_RESERVED_KEYWORDS.add("DATEDIFF");
        DM_RESERVED_KEYWORDS.add("DATEPART");
        DM_RESERVED_KEYWORDS.add("DECIMAL");
        DM_RESERVED_KEYWORDS.add("DECLARE");
        DM_RESERVED_KEYWORDS.add("DECODE");
        DM_RESERVED_KEYWORDS.add("DEFAULT");
        DM_RESERVED_KEYWORDS.add("DELETE");
        DM_RESERVED_KEYWORDS.add("DESC");
        DM_RESERVED_KEYWORDS.add("DISABLE");
        DM_RESERVED_KEYWORDS.add("DISKSPACE");
        DM_RESERVED_KEYWORDS.add("DISTINCT");
        DM_RESERVED_KEYWORDS.add("DISTRIBUTED");
        DM_RESERVED_KEYWORDS.add("DO");
        DM_RESERVED_KEYWORDS.add("DOMAIN");
        DM_RESERVED_KEYWORDS.add("DOUBLE");
        DM_RESERVED_KEYWORDS.add("DROP");
        DM_RESERVED_KEYWORDS.add("ELSE");
        DM_RESERVED_KEYWORDS.add("ELSEIF");
        DM_RESERVED_KEYWORDS.add("ELSIF");
        DM_RESERVED_KEYWORDS.add("ENABLE");
        DM_RESERVED_KEYWORDS.add("END");
        DM_RESERVED_KEYWORDS.add("EQU");
        DM_RESERVED_KEYWORDS.add("EXCHANGE");
        DM_RESERVED_KEYWORDS.add("EXEC");
        DM_RESERVED_KEYWORDS.add("EXECUTE");
        DM_RESERVED_KEYWORDS.add("EXISTS");
        DM_RESERVED_KEYWORDS.add("EXIT");
        DM_RESERVED_KEYWORDS.add("EXPLAIN");
        DM_RESERVED_KEYWORDS.add("EXTERN");
        DM_RESERVED_KEYWORDS.add("EXTRACT");
        DM_RESERVED_KEYWORDS.add("FETCH");
        DM_RESERVED_KEYWORDS.add("FINAL");
        DM_RESERVED_KEYWORDS.add("FINALLY");
        DM_RESERVED_KEYWORDS.add("FIRST");
        DM_RESERVED_KEYWORDS.add("FLASHBACK");
        DM_RESERVED_KEYWORDS.add("FLOAT");
        DM_RESERVED_KEYWORDS.add("FOR");
        DM_RESERVED_KEYWORDS.add("FOREIGN");
        DM_RESERVED_KEYWORDS.add("FROM");
        DM_RESERVED_KEYWORDS.add("FULL");
        DM_RESERVED_KEYWORDS.add("FULLY");
        DM_RESERVED_KEYWORDS.add("FUNCTION");
        DM_RESERVED_KEYWORDS.add("GET");
        DM_RESERVED_KEYWORDS.add("GOTO");
        DM_RESERVED_KEYWORDS.add("GRANT");
        DM_RESERVED_KEYWORDS.add("GROUP");
        DM_RESERVED_KEYWORDS.add("GROUPING");
        DM_RESERVED_KEYWORDS.add("HAVING");
        DM_RESERVED_KEYWORDS.add("IDENTITY");
        DM_RESERVED_KEYWORDS.add("IF");
        DM_RESERVED_KEYWORDS.add("IFNULL");
        DM_RESERVED_KEYWORDS.add("IMMEDIATE");
        DM_RESERVED_KEYWORDS.add("IN");
        DM_RESERVED_KEYWORDS.add("INDEX");
        DM_RESERVED_KEYWORDS.add("INLINE");
        DM_RESERVED_KEYWORDS.add("INNER");
        DM_RESERVED_KEYWORDS.add("INSERT");
        DM_RESERVED_KEYWORDS.add("INT");
        DM_RESERVED_KEYWORDS.add("INTERSECT");
        DM_RESERVED_KEYWORDS.add("INTERVAL");
        DM_RESERVED_KEYWORDS.add("INTO");
        DM_RESERVED_KEYWORDS.add("IS");
        DM_RESERVED_KEYWORDS.add("JOIN");
        DM_RESERVED_KEYWORDS.add("KEEP");
        DM_RESERVED_KEYWORDS.add("LEADING");
        DM_RESERVED_KEYWORDS.add("LEFT");
        DM_RESERVED_KEYWORDS.add("LEXER");
        DM_RESERVED_KEYWORDS.add("LIKE");
        DM_RESERVED_KEYWORDS.add("LIST");
        DM_RESERVED_KEYWORDS.add("LNNVL");
        DM_RESERVED_KEYWORDS.add("LOGIN");
        DM_RESERVED_KEYWORDS.add("LOOP");
        DM_RESERVED_KEYWORDS.add("MEMBER");
        DM_RESERVED_KEYWORDS.add("MINUS");
        DM_RESERVED_KEYWORDS.add("MULTISET");
        DM_RESERVED_KEYWORDS.add("NATURAL");
        DM_RESERVED_KEYWORDS.add("NEW");
        DM_RESERVED_KEYWORDS.add("NEXT");
        DM_RESERVED_KEYWORDS.add("NOCOPY");
        DM_RESERVED_KEYWORDS.add("NOCYCLE");
        DM_RESERVED_KEYWORDS.add("NOT");
        DM_RESERVED_KEYWORDS.add("NULL");
        DM_RESERVED_KEYWORDS.add("OBJECT");
        DM_RESERVED_KEYWORDS.add("OF");
        DM_RESERVED_KEYWORDS.add("ON");
        DM_RESERVED_KEYWORDS.add("OR");
        DM_RESERVED_KEYWORDS.add("ORDER");
        DM_RESERVED_KEYWORDS.add("OUT");
        DM_RESERVED_KEYWORDS.add("OVER");
        DM_RESERVED_KEYWORDS.add("OVERLAY");
        DM_RESERVED_KEYWORDS.add("OVERRIDE");
        DM_RESERVED_KEYWORDS.add("PARTITION");
        DM_RESERVED_KEYWORDS.add("PENDANT");
        DM_RESERVED_KEYWORDS.add("PERCENT");
        DM_RESERVED_KEYWORDS.add("PRIMARY");
        DM_RESERVED_KEYWORDS.add("PRINT");
        DM_RESERVED_KEYWORDS.add("PRIOR");
        DM_RESERVED_KEYWORDS.add("PRIVATE");
        DM_RESERVED_KEYWORDS.add("PRIVILEGES");
        DM_RESERVED_KEYWORDS.add("PROCEDURE");
        DM_RESERVED_KEYWORDS.add("PROTECTED");
        DM_RESERVED_KEYWORDS.add("PUBLIC");
        DM_RESERVED_KEYWORDS.add("RAISE");
        DM_RESERVED_KEYWORDS.add("RECORD");
        DM_RESERVED_KEYWORDS.add("REF");
        DM_RESERVED_KEYWORDS.add("REFERENCE");
        DM_RESERVED_KEYWORDS.add("REFERENCES");
        DM_RESERVED_KEYWORDS.add("REFERENCING");
        DM_RESERVED_KEYWORDS.add("RELATIVE");
        DM_RESERVED_KEYWORDS.add("REPEAT");
        DM_RESERVED_KEYWORDS.add("REPLICATE");
        DM_RESERVED_KEYWORDS.add("RETURN");
        DM_RESERVED_KEYWORDS.add("RETURNING");
        DM_RESERVED_KEYWORDS.add("REVERSE");
        DM_RESERVED_KEYWORDS.add("REVOKE");
        DM_RESERVED_KEYWORDS.add("RIGHT");
        DM_RESERVED_KEYWORDS.add("ROLLBACK");
        DM_RESERVED_KEYWORDS.add("ROLLUP");
        DM_RESERVED_KEYWORDS.add("ROW");
        DM_RESERVED_KEYWORDS.add("ROWNUM");
        DM_RESERVED_KEYWORDS.add("ROWS");
        DM_RESERVED_KEYWORDS.add("SAVEPOINT");
        DM_RESERVED_KEYWORDS.add("SBYTE");
        DM_RESERVED_KEYWORDS.add("SCHEMA");
        DM_RESERVED_KEYWORDS.add("SEALED");
        DM_RESERVED_KEYWORDS.add("SECTION");
        DM_RESERVED_KEYWORDS.add("SELECT");
        DM_RESERVED_KEYWORDS.add("SET");
        DM_RESERVED_KEYWORDS.add("SETS");
        DM_RESERVED_KEYWORDS.add("SHORT");
        DM_RESERVED_KEYWORDS.add("SIZEOF");
        DM_RESERVED_KEYWORDS.add("SOME");
        DM_RESERVED_KEYWORDS.add("STATIC");
        DM_RESERVED_KEYWORDS.add("STRUCT");
        DM_RESERVED_KEYWORDS.add("SUBPARTITION");
        DM_RESERVED_KEYWORDS.add("SWITCH");
        DM_RESERVED_KEYWORDS.add("SYNONYM");
        DM_RESERVED_KEYWORDS.add("TABLE");
        DM_RESERVED_KEYWORDS.add("THROW");
        DM_RESERVED_KEYWORDS.add("TIMESTAMPADD");
        DM_RESERVED_KEYWORDS.add("TIMESTAMPDIFF");
        DM_RESERVED_KEYWORDS.add("TO");
        DM_RESERVED_KEYWORDS.add("TOP");
        DM_RESERVED_KEYWORDS.add("TRAILING");
        DM_RESERVED_KEYWORDS.add("TREAT");
        DM_RESERVED_KEYWORDS.add("TRIGGER");
        DM_RESERVED_KEYWORDS.add("TRIM");
        DM_RESERVED_KEYWORDS.add("TRUNCATE");
        DM_RESERVED_KEYWORDS.add("TRY");
        DM_RESERVED_KEYWORDS.add("TYPEDEF");
        DM_RESERVED_KEYWORDS.add("TYPEOF");
        DM_RESERVED_KEYWORDS.add("UINT");
        DM_RESERVED_KEYWORDS.add("ULONG");
        DM_RESERVED_KEYWORDS.add("UNION");
        DM_RESERVED_KEYWORDS.add("UNIQUE");
        DM_RESERVED_KEYWORDS.add("UNTIL");
        DM_RESERVED_KEYWORDS.add("UPDATE");
        DM_RESERVED_KEYWORDS.add("USER");
        DM_RESERVED_KEYWORDS.add("USHORT");
        DM_RESERVED_KEYWORDS.add("USING");
        DM_RESERVED_KEYWORDS.add("VALUES");
        DM_RESERVED_KEYWORDS.add("VARRAY");
        DM_RESERVED_KEYWORDS.add("VERIFY");
        DM_RESERVED_KEYWORDS.add("VIEW");
        DM_RESERVED_KEYWORDS.add("VIRTUAL");
        DM_RESERVED_KEYWORDS.add("VOID");
        DM_RESERVED_KEYWORDS.add("WHEN");
        DM_RESERVED_KEYWORDS.add("WHENEVER");
        DM_RESERVED_KEYWORDS.add("WHERE");
        DM_RESERVED_KEYWORDS.add("WHILE");
        DM_RESERVED_KEYWORDS.add("WITH");
        DM_RESERVED_KEYWORDS.add("WITHIN");
        DM_RESERVED_KEYWORDS.add("XMLAGG");
        DM_RESERVED_KEYWORDS.add("XMLPARSE");
        DM_RESERVED_KEYWORDS.add("XMLQUERY");
        DM_RESERVED_KEYWORDS.add("XMLTABLE");
    }


    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return identifier != null && DM_RESERVED_KEYWORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    /**
     * SPI-facing conditional quoting: {@code null} and blank identifiers are
     * returned unchanged; identifiers that are already valid for the dialect
     * and are not reserved keywords are returned unquoted; anything else is
     * wrapped with double quotes, stripping one surrounding quote pair and
     * doubling every embedded double quote.
     */
    @Override
    public String quoteIdentifier(String identifier, Integer majorVersion, Integer minorVersion) {
        return quoteIdentifier(identifier);
    }

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
        if (isValidIdentifier(identifier)
                && !containsLowerCase(identifier)
                && !isReservedKeyword(identifier, null, null)) {
            return identifier;
        }
        return quoteIdentifierAlways(identifier);
    }

    /**
     * Unconditional quoting for DDL-generation call sites: wraps with double
     * quotes and doubling every embedded double quote, including quotes at the
     * raw name boundaries. Returns {@code null} for {@code null}.
     */
    @Override
    public String quoteIdentifierIgnoreCase(String identifier) {
        if (identifier == null) {
            return null;
        }
        if (StringUtils.isBlank(identifier)) {
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
     * Escapes a value interpolated into a single-quoted SQL string literal by
     * doubling every single quote. Returns {@code null} for {@code null}.
     */
    @Override
    public String escapeString(String str) {
        return StringUtils.replace(str, "'", "''");
    }

    @Override
    public String convertIdentifierCase(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        } else {
            return identifier.toUpperCase(Locale.ROOT);
        }
    }

    private static String escapeIdentifierContent(String identifier) {
        return identifier == null ? null : StringUtils.replace(identifier, "\"", "\"\"");
    }

    /**
     * Escapes identifier content for a position already surrounded by double
     * quotes. Returns {@code null} for {@code null}.
     */
    public static String escapeIdentifier(String identifier) {
        return escapeIdentifierContent(identifier);
    }

    @Override
    public String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "\"" + escapeIdentifierContent(identifier) + "\"";
    }

    public String quoteStringLiteral(String value) {
        return value == null ? null : "'" + escapeString(value) + "'";
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

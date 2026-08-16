package ai.chat2db.plugin.db2.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * DB2 dialect identifier processor: double-quoted identifiers with embedded-quote
 * doubling, and single-quote doubling for string literals. Shared stateless
 * instance available via {@link #INSTANCE} for call sites without MetaData access.
 */
public class Db2IdentifierProcessor extends DefaultSQLIdentifierProcessor {

    public static final Db2IdentifierProcessor INSTANCE = new Db2IdentifierProcessor();

    private static final Set<String> RESERVED_KEYWORDS = Set.copyOf(Arrays.asList((
            "ACTIVATE ADD AFTER ALIAS ALL ALLOCATE ALLOW ALTER AND ANY AS ASENSITIVE ASSOCIATE AT ATTRIBUTES "
                    + "AUDIT AUTHORIZATION AUX AUXILIARY BEFORE BEGIN BETWEEN BINARY BUFFERPOOL BY CACHE CALL CALLED "
                    + "CAPTURE CARDINALITY CASCADED CASE CAST CCSID CHAR CHARACTER CHECK CLONE CLOSE CLUSTER COLLECTION "
                    + "COLLID COLUMN COMMENT COMMIT CONCAT CONDITION CONNECT CONNECTION CONSTRAINT CONTAINS CONTINUE "
                    + "COUNT COUNT_BIG CREATE CROSS CURRENT CURRENT_DATE CURRENT_LC_CTYPE CURRENT_PATH CURRENT_SCHEMA "
                    + "CURRENT_SERVER CURRENT_TIME CURRENT_TIMESTAMP CURRENT_TIMEZONE CURRENT_USER CURSOR CYCLE DATA "
                    + "DATABASE DATAPARTITIONNAME DATAPARTITIONNUM DATE DAY DAYS DB2GENERAL DB2GENRL DB2SQL DBINFO "
                    + "DBPARTITIONNAME DBPARTITIONNUM DEALLOCATE DECLARE DEFAULT DEFAULTS DEFINITION DELETE DENSERANK "
                    + "DENSE_RANK DESCRIBE DESCRIPTOR DETERMINISTIC DIAGNOSTICS DISABLE DISALLOW DISCONNECT DISTINCT "
                    + "DO DOCUMENT DOUBLE DROP DSSIZE DYNAMIC EACH EDITPROC ELSE ELSEIF ENABLE ENCODING ENCRYPTION END "
                    + "ENDING ERASE ESCAPE EVERY EXCEPT EXCEPTION EXCLUDING EXCLUSIVE EXECUTE EXISTS EXIT EXPLAIN "
                    + "EXTENDED EXTERNAL EXTRACT FENCED FETCH FIELDPROC FILE FINAL FIRST FOR FOREIGN FREE FROM FULL "
                    + "FUNCTION GENERAL GENERATED GET GLOBAL GO GOTO GRANT GRAPHIC GROUP HANDLER HASH HASHED_VALUE "
                    + "HAVING HINT HOLD HOUR HOURS IDENTITY IF IMMEDIATE IMPORT IN INCLUDING INCLUSIVE INCREMENT INDEX "
                    + "INDICATOR INDICATORS INF INFINITY INHERIT INNER INOUT INSERT INSENSITIVE INTEGRITY INTERSECT "
                    + "INTO IS ISNULL ISOBID ISOLATION ITERATE JAR JAVA JOIN KEEP KEY LABEL LANGUAGE LAST LATERAL "
                    + "LC_CTYPE LEAVE LEFT LIKE LIMIT LINKTYPE LOCAL LOCALDATE LOCALE LOCALTIME LOCALTIMESTAMP LOCATOR "
                    + "LOCATORS LOCK LOCKMAX LOCKSIZE LONG LOOP MAINTAINED MATERIALIZED MAXVALUE MICROSECOND "
                    + "MICROSECONDS MINUTE MINUTES MINVALUE MODE MODIFIES MONTH MONTHS NAN NEW NEW_TABLE NEXTVAL NO "
                    + "NOCACHE NOCYCLE NODENAME NODENUMBER NOMAXVALUE NOMINVALUE NONE NOORDER NORMALIZED NOT NOTNULL "
                    + "NULL NULLS NUMPARTS OBID OF OFF OFFSET OLD OLD_TABLE ON OPEN OPTIMIZATION OPTIMIZE OPTION OR "
                    + "ORDER OUT OUTER OVER OVERRIDING PACKAGE PADDED PAGESIZE PARAMETER PART PARTITION PARTITIONED "
                    + "PARTITIONING PARTITIONS PASSWORD PATH PERCENT PIECESIZE PLAN POSITION PRECISION PREPARE PREVVAL "
                    + "PRIMARY PRIQTY PRIVILEGES PROCEDURE PROGRAM PSID PUBLIC QUERY QUERYNO RANGE RANK READ READS "
                    + "RECOVERY REFERENCES REFERENCING REFRESH RELEASE RENAME REPEAT RESET RESIGNAL RESTART RESTRICT "
                    + "RESULT RESULT_SET_LOCATOR RETURN RETURNS REVOKE RIGHT ROLE ROLLBACK ROW ROWNUMBER ROW_NUMBER "
                    + "ROWS ROWSET RRN RUN SAVEPOINT SCHEMA SCROLL SEARCH SECOND SECONDS SECURITY SELECT SENSITIVE "
                    + "SEQUENCE SESSION SESSION_USER SET SIGNAL SIMPLE SNAN SOME SOURCE SPECIFIC SQL SQLID STACKED "
                    + "STANDARD START STARTING STATEMENT STATIC STAY STOGROUP STORES STYLE SUBSTRING SUMMARY SYNONYM "
                    + "SYSFUN SYSIBM SYSPROC SYSTEM SYSTEM_USER TABLE TABLESPACE THEN TIME TIMESTAMP TO TRANSACTION "
                    + "TRIGGER TRIM TRUNCATE TYPE UNDO UNION UNIQUE UNTIL UPDATE USAGE USER USING VALUE VALUES VARIABLE "
                    + "VARIANT VCAT VERSION VIEW VOLATILE VOLUMES WHEN WHENEVER WHERE WHILE WITH WITHOUT WLM WRITE "
                    + "XMLELEMENT XMLEXISTS XMLNAMESPACES YEAR YEARS").split("\\s+")));

    /**
     * SPI-facing conditional quoting: null passes through, blank is returned
     * unchanged, an identifier that is already a valid plain identifier (and not
     * a reserved keyword) is returned unquoted; anything else is wrapped with
     * double quotes via {@link #quoteIdentifierAlways(String)}.
     */
    @Override
    public String quoteIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
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
     * Unconditional quoting for DDL-generation call sites: null passes through,
     * anything else is wrapped with double quotes and every embedded double
     * quote is doubled.
     */
    public String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "\"" + StringUtils.replace(identifier, "\"", "\"\"") + "\"";
    }

    @Override
    public String quoteIdentifierIgnoreCase(String identifier) {
        if (identifier == null || StringUtils.isBlank(identifier)) {
            return identifier;
        }
        if (isValidIdentifier(identifier) && !isReservedKeyword(identifier, null, null)) {
            return identifier;
        }
        return quoteIdentifierAlways(identifier);
    }

    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return identifier != null && RESERVED_KEYWORDS.contains(identifier.toUpperCase(Locale.ROOT));
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
        if (identifier == null) {
            return "";
        }
        return StringUtils.replace(identifier, "\"", "\"\"");
    }

    /**
     * Escapes identifier content for a position already surrounded by double
     * quotes by doubling every embedded double quote.
     */
    public static String escapeIdentifier(String identifier) {
        return escapeIdentifierContent(identifier);
    }
}

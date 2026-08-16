package ai.chat2db.plugin.mysql.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;


import static ai.chat2db.plugin.mysql.constant.MysqlIdentifierProcessorConstants.*;
public class MysqlIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    /**
     * Shared stateless instance for call sites without MetaData access.
     */
    public static final MysqlIdentifierProcessor INSTANCE = new MysqlIdentifierProcessor();

    private static final Set<String> MYSQL_RESERVED_KEYWORDS = new HashSet<>();

    static {
        MYSQL_RESERVED_KEYWORDS.add("ACCESSIBLE");
        MYSQL_RESERVED_KEYWORDS.add("ADD");
        MYSQL_RESERVED_KEYWORDS.add("ALL");
        MYSQL_RESERVED_KEYWORDS.add("ALTER");
        MYSQL_RESERVED_KEYWORDS.add("ANALYZE");
        MYSQL_RESERVED_KEYWORDS.add("AND");
        MYSQL_RESERVED_KEYWORDS.add("AS");
        MYSQL_RESERVED_KEYWORDS.add("ASC");
        MYSQL_RESERVED_KEYWORDS.add("ASENSITIVE");
        MYSQL_RESERVED_KEYWORDS.add("BEFORE");
        MYSQL_RESERVED_KEYWORDS.add("BETWEEN");
        MYSQL_RESERVED_KEYWORDS.add("BIGINT");
        MYSQL_RESERVED_KEYWORDS.add("BINARY");
        MYSQL_RESERVED_KEYWORDS.add("BLOB");
        MYSQL_RESERVED_KEYWORDS.add("BOTH");
        MYSQL_RESERVED_KEYWORDS.add("BY");
        MYSQL_RESERVED_KEYWORDS.add("CALL");
        MYSQL_RESERVED_KEYWORDS.add("CASCADE");
        MYSQL_RESERVED_KEYWORDS.add("CASE");
        MYSQL_RESERVED_KEYWORDS.add("CHANGE");
        MYSQL_RESERVED_KEYWORDS.add("CHAR");
        MYSQL_RESERVED_KEYWORDS.add("CHARACTER");
        MYSQL_RESERVED_KEYWORDS.add("CHECK");
        MYSQL_RESERVED_KEYWORDS.add("COLLATE");
        MYSQL_RESERVED_KEYWORDS.add("COLUMN");
        MYSQL_RESERVED_KEYWORDS.add("CONDITION");
        MYSQL_RESERVED_KEYWORDS.add("CONSTRAINT");
        MYSQL_RESERVED_KEYWORDS.add("CONTINUE");
        MYSQL_RESERVED_KEYWORDS.add("CONVERT");
        MYSQL_RESERVED_KEYWORDS.add("CREATE");
        MYSQL_RESERVED_KEYWORDS.add("CROSS");
        MYSQL_RESERVED_KEYWORDS.add("CUBE");
        MYSQL_RESERVED_KEYWORDS.add("CUME_DIST");
        MYSQL_RESERVED_KEYWORDS.add("CURRENT_DATE");
        MYSQL_RESERVED_KEYWORDS.add("CURRENT_TIME");
        MYSQL_RESERVED_KEYWORDS.add("CURRENT_TIMESTAMP");
        MYSQL_RESERVED_KEYWORDS.add("CURRENT_USER");
        MYSQL_RESERVED_KEYWORDS.add("CURSOR");
        MYSQL_RESERVED_KEYWORDS.add("DATABASE");
        MYSQL_RESERVED_KEYWORDS.add("DATABASES");
        MYSQL_RESERVED_KEYWORDS.add("DAY_HOUR");
        MYSQL_RESERVED_KEYWORDS.add("DAY_MICROSECOND");
        MYSQL_RESERVED_KEYWORDS.add("DAY_MINUTE");
        MYSQL_RESERVED_KEYWORDS.add("DAY_SECOND");
        MYSQL_RESERVED_KEYWORDS.add("DEC");
        MYSQL_RESERVED_KEYWORDS.add("DECIMAL");
        MYSQL_RESERVED_KEYWORDS.add("DECLARE");
        MYSQL_RESERVED_KEYWORDS.add("DEFAULT");
        MYSQL_RESERVED_KEYWORDS.add("DELAYED");
        MYSQL_RESERVED_KEYWORDS.add("DELETE");
        MYSQL_RESERVED_KEYWORDS.add("DENSE_RANK");
        MYSQL_RESERVED_KEYWORDS.add("DESC");
        MYSQL_RESERVED_KEYWORDS.add("DESCRIBE");
        MYSQL_RESERVED_KEYWORDS.add("DETERMINISTIC");
        MYSQL_RESERVED_KEYWORDS.add("DISTINCT");
        MYSQL_RESERVED_KEYWORDS.add("DISTINCTROW");
        MYSQL_RESERVED_KEYWORDS.add("DIV");
        MYSQL_RESERVED_KEYWORDS.add("DOUBLE");
        MYSQL_RESERVED_KEYWORDS.add("DROP");
        MYSQL_RESERVED_KEYWORDS.add("DUAL");
        MYSQL_RESERVED_KEYWORDS.add("EACH");
        MYSQL_RESERVED_KEYWORDS.add("ELSE");
        MYSQL_RESERVED_KEYWORDS.add("ELSEIF");
        MYSQL_RESERVED_KEYWORDS.add("EMPTY");
        MYSQL_RESERVED_KEYWORDS.add("ENCLOSED");
        MYSQL_RESERVED_KEYWORDS.add("ESCAPED");
        MYSQL_RESERVED_KEYWORDS.add("EXCEPT");
        MYSQL_RESERVED_KEYWORDS.add("EXISTS");
        MYSQL_RESERVED_KEYWORDS.add("EXIT");
        MYSQL_RESERVED_KEYWORDS.add("EXPLAIN");
        MYSQL_RESERVED_KEYWORDS.add("FALSE");
        MYSQL_RESERVED_KEYWORDS.add("FETCH");
        MYSQL_RESERVED_KEYWORDS.add("FIRST_VALUE");
        MYSQL_RESERVED_KEYWORDS.add("FLOAT");
        MYSQL_RESERVED_KEYWORDS.add("FLOAT4");
        MYSQL_RESERVED_KEYWORDS.add("FLOAT8");
        MYSQL_RESERVED_KEYWORDS.add("FOR");
        MYSQL_RESERVED_KEYWORDS.add("FORCE");
        MYSQL_RESERVED_KEYWORDS.add("FOREIGN");
        MYSQL_RESERVED_KEYWORDS.add("FROM");
        MYSQL_RESERVED_KEYWORDS.add("FULLTEXT");
        MYSQL_RESERVED_KEYWORDS.add("FUNCTION");
        MYSQL_RESERVED_KEYWORDS.add("GENERATED");
        MYSQL_RESERVED_KEYWORDS.add("GET");
        MYSQL_RESERVED_KEYWORDS.add("GRANT");
        MYSQL_RESERVED_KEYWORDS.add("GROUP");
        MYSQL_RESERVED_KEYWORDS.add("GROUPING");
        MYSQL_RESERVED_KEYWORDS.add("GROUPS");
        MYSQL_RESERVED_KEYWORDS.add("HAVING");
        MYSQL_RESERVED_KEYWORDS.add("HIGH_PRIORITY");
        MYSQL_RESERVED_KEYWORDS.add("HOUR_MICROSECOND");
        MYSQL_RESERVED_KEYWORDS.add("HOUR_MINUTE");
        MYSQL_RESERVED_KEYWORDS.add("HOUR_SECOND");
        MYSQL_RESERVED_KEYWORDS.add("IF");
        MYSQL_RESERVED_KEYWORDS.add("IGNORE");
        MYSQL_RESERVED_KEYWORDS.add("IN");
        MYSQL_RESERVED_KEYWORDS.add("INDEX");
        MYSQL_RESERVED_KEYWORDS.add("INFILE");
        MYSQL_RESERVED_KEYWORDS.add("INNER");
        MYSQL_RESERVED_KEYWORDS.add("INOUT");
        MYSQL_RESERVED_KEYWORDS.add("INSENSITIVE");
        MYSQL_RESERVED_KEYWORDS.add("INSERT");
        MYSQL_RESERVED_KEYWORDS.add("INT");
        MYSQL_RESERVED_KEYWORDS.add("INT1");
        MYSQL_RESERVED_KEYWORDS.add("INT2");
        MYSQL_RESERVED_KEYWORDS.add("INT3");
        MYSQL_RESERVED_KEYWORDS.add("INT4");
        MYSQL_RESERVED_KEYWORDS.add("INT8");
        MYSQL_RESERVED_KEYWORDS.add("INTEGER");
        MYSQL_RESERVED_KEYWORDS.add("INTERSECT");
        MYSQL_RESERVED_KEYWORDS.add("INTERVAL");
        MYSQL_RESERVED_KEYWORDS.add("INTO");
        MYSQL_RESERVED_KEYWORDS.add("IO_AFTER_GTIDS");
        MYSQL_RESERVED_KEYWORDS.add("IO_BEFORE_GTIDS");
        MYSQL_RESERVED_KEYWORDS.add("IS");
        MYSQL_RESERVED_KEYWORDS.add("ITERATE");
        MYSQL_RESERVED_KEYWORDS.add("JOIN");
        MYSQL_RESERVED_KEYWORDS.add("JSON_TABLE");
        MYSQL_RESERVED_KEYWORDS.add("KEY");
        MYSQL_RESERVED_KEYWORDS.add("KEYS");
        MYSQL_RESERVED_KEYWORDS.add("KILL");
        MYSQL_RESERVED_KEYWORDS.add("LAG");
        MYSQL_RESERVED_KEYWORDS.add("LAST_VALUE");
        MYSQL_RESERVED_KEYWORDS.add("LATERAL");
        MYSQL_RESERVED_KEYWORDS.add("LEAD");
        MYSQL_RESERVED_KEYWORDS.add("LEADING");
        MYSQL_RESERVED_KEYWORDS.add("LEAVE");
        MYSQL_RESERVED_KEYWORDS.add("LEFT");
        MYSQL_RESERVED_KEYWORDS.add("LIKE");
        MYSQL_RESERVED_KEYWORDS.add("LIMIT");
        MYSQL_RESERVED_KEYWORDS.add("LINEAR");
        MYSQL_RESERVED_KEYWORDS.add("LINES");
        MYSQL_RESERVED_KEYWORDS.add("LOAD");
        MYSQL_RESERVED_KEYWORDS.add("LOCALTIME");
        MYSQL_RESERVED_KEYWORDS.add("LOCALTIMESTAMP");
        MYSQL_RESERVED_KEYWORDS.add("LOCK");
        MYSQL_RESERVED_KEYWORDS.add("LONG");
        MYSQL_RESERVED_KEYWORDS.add("LONGBLOB");
        MYSQL_RESERVED_KEYWORDS.add("LONGTEXT");
        MYSQL_RESERVED_KEYWORDS.add("LOOP");
        MYSQL_RESERVED_KEYWORDS.add("LOW_PRIORITY");
        MYSQL_RESERVED_KEYWORDS.add("MANUAL");
        MYSQL_RESERVED_KEYWORDS.add("MASTER_BIND");
        MYSQL_RESERVED_KEYWORDS.add("MASTER_SSL_VERIFY_SERVER_CERT");
        MYSQL_RESERVED_KEYWORDS.add("MATCH");
        MYSQL_RESERVED_KEYWORDS.add("MAXVALUE");
        MYSQL_RESERVED_KEYWORDS.add("MEDIUMBLOB");
        MYSQL_RESERVED_KEYWORDS.add("MEDIUMINT");
        MYSQL_RESERVED_KEYWORDS.add("MEDIUMTEXT");
        MYSQL_RESERVED_KEYWORDS.add("MIDDLEINT");
        MYSQL_RESERVED_KEYWORDS.add("MINUTE_MICROSECOND");
        MYSQL_RESERVED_KEYWORDS.add("MINUTE_SECOND");
        MYSQL_RESERVED_KEYWORDS.add("MOD");
        MYSQL_RESERVED_KEYWORDS.add("MODIFIES");
        MYSQL_RESERVED_KEYWORDS.add("NATURAL");
        MYSQL_RESERVED_KEYWORDS.add("NOT");
        MYSQL_RESERVED_KEYWORDS.add("NO_WRITE_TO_BINLOG");
        MYSQL_RESERVED_KEYWORDS.add("NTH_VALUE");
        MYSQL_RESERVED_KEYWORDS.add("NTILE");
        MYSQL_RESERVED_KEYWORDS.add("NULL");
        MYSQL_RESERVED_KEYWORDS.add("NUMERIC");
        MYSQL_RESERVED_KEYWORDS.add("OF");
        MYSQL_RESERVED_KEYWORDS.add("ON");
        MYSQL_RESERVED_KEYWORDS.add("OPTIMIZE");
        MYSQL_RESERVED_KEYWORDS.add("OPTIMIZER_COSTS");
        MYSQL_RESERVED_KEYWORDS.add("OPTION");
        MYSQL_RESERVED_KEYWORDS.add("OPTIONALLY");
        MYSQL_RESERVED_KEYWORDS.add("OR");
        MYSQL_RESERVED_KEYWORDS.add("ORDER");
        MYSQL_RESERVED_KEYWORDS.add("OUT");
        MYSQL_RESERVED_KEYWORDS.add("OUTER");
        MYSQL_RESERVED_KEYWORDS.add("OUTFILE");
        MYSQL_RESERVED_KEYWORDS.add("OVER");
        MYSQL_RESERVED_KEYWORDS.add("PARALLEL");
        MYSQL_RESERVED_KEYWORDS.add("PARTITION");
        MYSQL_RESERVED_KEYWORDS.add("PERCENT_RANK");
        MYSQL_RESERVED_KEYWORDS.add("PRECISION");
        MYSQL_RESERVED_KEYWORDS.add("PRIMARY");
        MYSQL_RESERVED_KEYWORDS.add("PROCEDURE");
        MYSQL_RESERVED_KEYWORDS.add("PURGE");
        MYSQL_RESERVED_KEYWORDS.add("QUALIFY");
        MYSQL_RESERVED_KEYWORDS.add("RANGE");
        MYSQL_RESERVED_KEYWORDS.add("RANK");
        MYSQL_RESERVED_KEYWORDS.add("READ");
        MYSQL_RESERVED_KEYWORDS.add("READS");
        MYSQL_RESERVED_KEYWORDS.add("READ_WRITE");
        MYSQL_RESERVED_KEYWORDS.add("REAL");
        MYSQL_RESERVED_KEYWORDS.add("RECURSIVE");
        MYSQL_RESERVED_KEYWORDS.add("REFERENCES");
        MYSQL_RESERVED_KEYWORDS.add("REGEXP");
        MYSQL_RESERVED_KEYWORDS.add("RELEASE");
        MYSQL_RESERVED_KEYWORDS.add("RENAME");
        MYSQL_RESERVED_KEYWORDS.add("REPEAT");
        MYSQL_RESERVED_KEYWORDS.add("REPLACE");
        MYSQL_RESERVED_KEYWORDS.add("REQUIRE");
        MYSQL_RESERVED_KEYWORDS.add("RESIGNAL");
        MYSQL_RESERVED_KEYWORDS.add("RESTRICT");
        MYSQL_RESERVED_KEYWORDS.add("RETURN");
        MYSQL_RESERVED_KEYWORDS.add("REVOKE");
        MYSQL_RESERVED_KEYWORDS.add("RIGHT");
        MYSQL_RESERVED_KEYWORDS.add("RLIKE");
        MYSQL_RESERVED_KEYWORDS.add("ROW");
        MYSQL_RESERVED_KEYWORDS.add("ROWS");
        MYSQL_RESERVED_KEYWORDS.add("ROW_NUMBER");
        MYSQL_RESERVED_KEYWORDS.add("SCHEMA");
        MYSQL_RESERVED_KEYWORDS.add("SCHEMAS");
        MYSQL_RESERVED_KEYWORDS.add("SECOND_MICROSECOND");
        MYSQL_RESERVED_KEYWORDS.add("SELECT");
        MYSQL_RESERVED_KEYWORDS.add("SENSITIVE");
        MYSQL_RESERVED_KEYWORDS.add("SEPARATOR");
        MYSQL_RESERVED_KEYWORDS.add("SET");
        MYSQL_RESERVED_KEYWORDS.add("SHOW");
        MYSQL_RESERVED_KEYWORDS.add("SIGNAL");
        MYSQL_RESERVED_KEYWORDS.add("SMALLINT");
        MYSQL_RESERVED_KEYWORDS.add("SPATIAL");
        MYSQL_RESERVED_KEYWORDS.add("SPECIFIC");
        MYSQL_RESERVED_KEYWORDS.add("SQL");
        MYSQL_RESERVED_KEYWORDS.add("SQLEXCEPTION");
        MYSQL_RESERVED_KEYWORDS.add("SQLSTATE");
        MYSQL_RESERVED_KEYWORDS.add("SQLWARNING");
        MYSQL_RESERVED_KEYWORDS.add("SQL_BIG_RESULT");
        MYSQL_RESERVED_KEYWORDS.add("SQL_CALC_FOUND_ROWS");
        MYSQL_RESERVED_KEYWORDS.add("SQL_SMALL_RESULT");
        MYSQL_RESERVED_KEYWORDS.add("SSL");
        MYSQL_RESERVED_KEYWORDS.add("STARTING");
        MYSQL_RESERVED_KEYWORDS.add("STORED");
        MYSQL_RESERVED_KEYWORDS.add("STRAIGHT_JOIN");
        MYSQL_RESERVED_KEYWORDS.add("SYSTEM");
        MYSQL_RESERVED_KEYWORDS.add("TABLE");
        MYSQL_RESERVED_KEYWORDS.add("TABLESAMPLE");
        MYSQL_RESERVED_KEYWORDS.add("TERMINATED");
        MYSQL_RESERVED_KEYWORDS.add("THEN");
        MYSQL_RESERVED_KEYWORDS.add("TINYBLOB");
        MYSQL_RESERVED_KEYWORDS.add("TINYINT");
        MYSQL_RESERVED_KEYWORDS.add("TINYTEXT");
        MYSQL_RESERVED_KEYWORDS.add("TO");
        MYSQL_RESERVED_KEYWORDS.add("TRAILING");
        MYSQL_RESERVED_KEYWORDS.add("TRIGGER");
        MYSQL_RESERVED_KEYWORDS.add("TRUE");
        MYSQL_RESERVED_KEYWORDS.add("UNDO");
        MYSQL_RESERVED_KEYWORDS.add("UNION");
        MYSQL_RESERVED_KEYWORDS.add("UNIQUE");
        MYSQL_RESERVED_KEYWORDS.add("UNLOCK");
        MYSQL_RESERVED_KEYWORDS.add("UNSIGNED");
        MYSQL_RESERVED_KEYWORDS.add("UPDATE");
        MYSQL_RESERVED_KEYWORDS.add("USAGE");
        MYSQL_RESERVED_KEYWORDS.add("USE");
        MYSQL_RESERVED_KEYWORDS.add("USING");
        MYSQL_RESERVED_KEYWORDS.add("UTC_DATE");
        MYSQL_RESERVED_KEYWORDS.add("UTC_TIME");
        MYSQL_RESERVED_KEYWORDS.add("UTC_TIMESTAMP");
        MYSQL_RESERVED_KEYWORDS.add("VALUES");
        MYSQL_RESERVED_KEYWORDS.add("VARBINARY");
        MYSQL_RESERVED_KEYWORDS.add("VARCHAR");
        MYSQL_RESERVED_KEYWORDS.add("VARCHARACTER");
        MYSQL_RESERVED_KEYWORDS.add("VARYING");
        MYSQL_RESERVED_KEYWORDS.add("VIRTUAL");
        MYSQL_RESERVED_KEYWORDS.add("WHEN");
        MYSQL_RESERVED_KEYWORDS.add("WHERE");
        MYSQL_RESERVED_KEYWORDS.add("WHILE");
        MYSQL_RESERVED_KEYWORDS.add("WINDOW");
        MYSQL_RESERVED_KEYWORDS.add("WITH");
        MYSQL_RESERVED_KEYWORDS.add("WRITE");
        MYSQL_RESERVED_KEYWORDS.add("XOR");
        MYSQL_RESERVED_KEYWORDS.add("YEAR_MONTH");
        MYSQL_RESERVED_KEYWORDS.add("ZEROFILL");
        MYSQL_RESERVED_KEYWORDS.add("_FILENAME");
    }


    @Override
    public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
        return MYSQL_RESERVED_KEYWORDS.contains(identifier);
    }

    /**
     * SPI-facing conditional quote: identifiers that are already valid plain identifiers
     * and not reserved keywords pass through unquoted; anything else is safely quoted.
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
        if (isValidIdentifier(identifier) && !isReservedKeyword(identifier.toUpperCase(), null, null)) {
            return identifier;
        }
        return quoteIdentifierAlways(identifier);
    }

    /**
     * Escapes a value interpolated into a single-quoted SQL string literal (surrounding
     * quotes NOT added). MySQL treats backslash as an escape character, so backslashes
     * are doubled before single quotes (mirrors MysqlAccountSqlBuilder.stringLiteral).
     */
    @Override
    public String escapeString(String str) {
        if (str == null) {
            return null;
        }
        StringBuilder escaped = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char current = str.charAt(i);
            switch (current) {
                case '\0' -> escaped.append("\\0");
                case '\b' -> escaped.append("\\b");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case 26 -> escaped.append("\\Z");
                case '\\' -> escaped.append("\\\\");
                case '\'' -> escaped.append("''");
                default -> escaped.append(current);
            }
        }
        return escaped.toString();
    }

    @Override
    public String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "`" + identifier.replace("`", "``") + "`";
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
        if (StringUtils.isBlank(identifier)) {
            return false;
        }
        if (identifier.startsWith("`") && identifier.endsWith("`")) {
            return true;
        }
        return identifier.startsWith("\"") && identifier.endsWith("\"");
    }
}

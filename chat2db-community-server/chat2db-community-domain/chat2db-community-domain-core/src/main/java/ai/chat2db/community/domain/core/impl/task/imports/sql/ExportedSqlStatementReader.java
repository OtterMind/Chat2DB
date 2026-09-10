package ai.chat2db.community.domain.core.impl.task.imports.sql;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLDefaultExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLLiteralExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement.ValuesClause;
import com.alibaba.druid.sql.ast.statement.SQLMergeStatement;
import com.alibaba.druid.sql.ast.statement.SQLReplaceStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleInsertStatement;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGInsertStatement;
import com.alibaba.druid.sql.dialect.sqlserver.ast.stmt.SQLServerInsertStatement;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams SQL exported by database management tools through deterministic lexical boundaries.
 * This reader is opt-in: callers must supply an explicit exporter profile before wrapper
 * statements are filtered from the script.
 */
final class ExportedSqlStatementReader {

    private static final long DEFAULT_MAX_SOURCE_BYTES = 1024L * 1024L * 1024L;
    private static final long HARD_MAX_SOURCE_BYTES = 1024L * 1024L * 1024L;
    private static final long DEFAULT_MAX_STATEMENT_CHARS = 128L * 1024L * 1024L;
    private static final long HARD_MAX_STATEMENT_CHARS = 128L * 1024L * 1024L;
    private static final int DEFAULT_MAX_STATEMENT_COUNT = 1_000_000;
    private static final int HARD_MAX_STATEMENT_COUNT = 1_000_000;
    private static final int DEFAULT_MAX_DISTINCT_TARGETS = 10_000;
    private static final int HARD_MAX_DISTINCT_TARGETS = 10_000;
    private static final int MAX_LEXICAL_CHARS = 1024 * 1024;
    private static final int RETAINED_BUFFER_CHARS = 1024 * 1024;
    private static final int MAX_DELIMITER_CHARS = 32;
    private static final int PROGRESS_INTERVAL = 1000;
    private static final long PROGRESS_BYTE_INTERVAL = 1024L * 1024L;

    private static final Pattern DELIMITER_DIRECTIVE = Pattern.compile(
            "^\\s*DELIMITER\\s+(\\S+)\\s*(?:(?:--|#).*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GO_DIRECTIVE = Pattern.compile(
            "^\\s*GO(?:\\s+(\\d+))?\\s*(?:--.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GO_LIKE_DIRECTIVE = Pattern.compile(
            "^\\s*GO(?:\\s|;|$).*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORACLE_BLOCK = Pattern.compile(
            "^(?:DECLARE|BEGIN)\\b|^CREATE\\s+(?:OR\\s+REPLACE\\s+)?"
                    + "(?:(?:NON)?EDITIONABLE\\s+)?"
                    + "(?:PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE|"
                    + "(?:AND\\s+COMPILE\\s+)?JAVA\\s+SOURCE)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQLSERVER_GO_BLOCK = Pattern.compile(
            "^(?:(?:CREATE\\s+(?:OR\\s+ALTER\\s+)?)|ALTER\\s+)"
                    + "(?:PROCEDURE|PROC|FUNCTION|TRIGGER|VIEW)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COPY_FROM_STDIN = Pattern.compile(
            "^COPY\\b.*\\bFROM\\s+STDIN\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FROM_STDIN_CLAUSE = Pattern.compile(
            "\\bFROM\\s+STDIN\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENROWSET_BULK = Pattern.compile(
            "\\bOPENROWSET\\s*\\(\\s*BULK\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTITY_INSERT = Pattern.compile(
            "^SET\\s+IDENTITY_INSERT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_EXEC = Pattern.compile(
            "^INSERT\\b[\\s\\S]*\\bEXEC(?:UTE)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQLSERVER_OUTPUT_INTO = Pattern.compile(
            "\\bOUTPUT\\b[\\s\\S]*\\bINTO\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_STATISTICS = Pattern.compile(
            "^UPDATE\\s+STATISTICS\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSTGRES_SETVAL = Pattern.compile(
            "(?<![\\p{L}\\p{N}_$])(?:(?:\"PG_CATALOG\"|PG_CATALOG)\\s*\\.\\s*)?"
                    + "(?:\"SETVAL\"|SETVAL)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSTGRES_LARGE_OBJECT = Pattern.compile(
            "(?<![\\p{L}\\p{N}_$])(?:(?:\"PG_CATALOG\"|PG_CATALOG)\\s*\\.\\s*)?"
                    + "(?:\"(?:LO_[A-Z0-9_]+|LOREAD|LOWRITE)\"|"
                    + "(?:LO_[A-Z0-9_]+|LOREAD|LOWRITE))\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final String DIRECT_IDENTIFIER =
            "(?:`(?:``|[^`])+`|\"(?:\"\"|[^\"])+\"|\\[(?:\\]\\]|[^\\]])+\\]|[\\p{L}_][\\p{L}\\p{N}_$#@]*)";
    private static final String DIRECT_QUALIFIED_IDENTIFIER =
            DIRECT_IDENTIFIER + "(?:\\s*\\.\\s*" + DIRECT_IDENTIFIER + "){0,2}";
    private static final Pattern DIRECT_INSERT_TARGET = Pattern.compile(
            "^\\s*(?:INSERT|UPSERT)\\s+(?:(?:OR\\s+(?:ROLLBACK|ABORT|REPLACE|FAIL|IGNORE)|IGNORE)\\s+)?"
                    + "INTO\\s+(" + DIRECT_QUALIFIED_IDENTIFIER + ")(?=\\s|\\(|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DIRECT_REPLACE_TARGET = Pattern.compile(
            "^\\s*REPLACE\\s+(?:(?:LOW_PRIORITY|DELAYED)\\s+)?(?:INTO\\s+)?("
                    + DIRECT_QUALIFIED_IDENTIFIER + ")(?=\\s|\\(|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DIRECT_UPDATE_TARGET = Pattern.compile(
            "^\\s*UPDATE\\s+(?:(?:LOW_PRIORITY|IGNORE)\\s+)*(" + DIRECT_QUALIFIED_IDENTIFIER
                    + ")\\s+SET\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DIRECT_DELETE_TARGET = Pattern.compile(
            "^\\s*DELETE\\s+FROM\\s+(" + DIRECT_QUALIFIED_IDENTIFIER + ")(?=\\s|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DIRECT_MERGE_TARGET = Pattern.compile(
            "^\\s*MERGE\\s+INTO\\s+(" + DIRECT_QUALIFIED_IDENTIFIER + ")(?=\\s|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final String DIRECT_COLUMN_LIST =
            "\\(\\s*" + DIRECT_IDENTIFIER + "(?:\\s*,\\s*" + DIRECT_IDENTIFIER + ")*\\s*\\)";
    private static final Pattern STRICT_INSERT_VALUES_PREFIX = Pattern.compile(
            "^\\s*(?:INSERT|UPSERT)\\s+INTO\\s+" + DIRECT_QUALIFIED_IDENTIFIER
                    + "\\s*(?:" + DIRECT_COLUMN_LIST + "\\s*)?VALUES?\\s*\\(",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SAFE_MYSQL_SESSION = Pattern.compile(
            "^SET\\s+(?:NAMES\\s+[A-Z0-9_]+(?:\\s+COLLATE\\s+[A-Z0-9_]+)?|"
                    + "(?:(?:@@(?:SESSION\\.)?)?(?:FOREIGN_KEY_CHECKS|UNIQUE_CHECKS|AUTOCOMMIT))"
                    + "\\s*=\\s*[01])$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_POSTGRES_SESSION = Pattern.compile(
            "^SET\\s+(?:STATEMENT_TIMEOUT|LOCK_TIMEOUT|IDLE_IN_TRANSACTION_SESSION_TIMEOUT|TRANSACTION_TIMEOUT)"
                    + "\\s*(?:=|TO)\\s*(?:'[^']*'|[A-Z0-9_.-]+)$|"
                    + "^SET\\s+CLIENT_ENCODING\\s*(?:=|TO)(?:\\s*[A-Z0-9_-]+)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_SQLSERVER_SESSION = Pattern.compile(
            "^SET\\s+(?:(?:ANSI_NULLS|ANSI_PADDING|QUOTED_IDENTIFIER)\\s+ON|NOCOUNT\\s+(?:ON|OFF))$",
            Pattern.CASE_INSENSITIVE);
    private static final String POSTGRES_IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")*\"|[\\p{L}_][\\p{L}\\p{N}_$]*)";
    private static final Pattern POSTGRES_RELATION = Pattern.compile(
            "^" + POSTGRES_IDENTIFIER + "(?:\\s*\\.\\s*" + POSTGRES_IDENTIFIER + ")?$",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern POSTGRES_COLUMN = Pattern.compile(
            "^" + POSTGRES_IDENTIFIER + "$", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Set<String> DATA_STATEMENT_KEYWORDS = Set.of(
            "INSERT", "REPLACE", "UPDATE", "DELETE", "MERGE", "UPSERT");
    private static final Set<String> SAFE_PSQL_COMMANDS = Set.of(
            "\\ECHO", "\\RESTRICT", "\\UNRESTRICT");
    private static final Set<String> SAFE_SQLCL_COMMANDS = Set.of(
            "BTITLE", "COLUMN", "PROMPT", "REM", "REMARK", "SET", "SHOW", "SPOOL", "TTITLE", "WHENEVER");
    private static final Set<String> UNSAFE_SQLCL_COMMANDS = Set.of(
            "ACCEPT", "APPEND", "ARCHIVE", "ATTRIBUTE", "BREAK", "CHANGE", "CLEAR", "COMPUTE", "CONNECT",
            "COPY", "DEFINE", "DEL", "DESCRIBE", "DISCONNECT", "EDIT", "EXEC", "EXECUTE", "EXIT", "GET",
            "HELP", "HOST", "INPUT", "LIST", "PASSWORD", "PAUSE", "PRINT", "RECOVER", "REPHEADER", "RUN",
            "SAVE", "SHUTDOWN", "START", "STARTUP", "STORE", "TIMING", "UNDEFINE", "VARIABLE", "XQUERY");
    private static final Set<String> STRUCTURAL_STATEMENT_KEYWORDS = Set.of(
            "ALTER", "COMMENT", "CREATE", "DROP", "GRANT", "LOCK", "RENAME", "REVOKE", "UNLOCK");

    private ExportedSqlStatementReader() {
    }

    static Inspection inspect(File source, Charset charset, ExporterProfile profile) throws IOException {
        return parse(source, charset, profile, StatementPolicy.TRUSTED, null, null);
    }

    static Inspection inspect(File source, Charset charset, ExporterProfile profile,
            ProgressListener progressListener) throws IOException {
        return parse(source, charset, profile, StatementPolicy.TRUSTED, null, progressListener);
    }

    static Inspection inspect(File source, Charset charset, ExporterProfile profile, String databaseType,
            ProgressListener progressListener) throws IOException {
        return inspect(source, charset, profile, databaseType, StatementPolicy.TRUSTED, progressListener);
    }

    static Inspection inspect(File source, Charset charset, ExporterProfile profile, String databaseType,
            StatementPolicy statementPolicy, ProgressListener progressListener) throws IOException {
        return parse(source, charset, profile, statementPolicy, SqlDialect.resolve(profile, databaseType), null,
                progressListener);
    }

    static Inspection streamDataStatements(File source, Charset charset, ExporterProfile profile,
            Consumer<String> statementConsumer, ProgressListener progressListener) throws IOException {
        if (statementConsumer == null) {
            throw new IllegalArgumentException("A data statement consumer is required");
        }
        return parse(source, charset, profile, StatementPolicy.TRUSTED,
                (sql, target) -> statementConsumer.accept(sql), progressListener);
    }

    static Inspection streamDataStatements(File source, Charset charset, ExporterProfile profile,
            String databaseType, Consumer<String> statementConsumer, ProgressListener progressListener)
            throws IOException {
        if (statementConsumer == null) {
            throw new IllegalArgumentException("A data statement consumer is required");
        }
        return parse(source, charset, profile, StatementPolicy.TRUSTED,
                SqlDialect.resolve(profile, databaseType),
                (sql, target) -> statementConsumer.accept(sql),
                progressListener);
    }

    static Inspection streamVerifiedDataStatements(File source, Charset charset, ExporterProfile profile,
            String databaseType, DataStatementConsumer statementConsumer, ProgressListener progressListener)
            throws IOException {
        return streamVerifiedDataStatements(source, charset, profile, databaseType, StatementPolicy.TRUSTED,
                statementConsumer, progressListener);
    }

    static Inspection streamVerifiedDataStatements(File source, Charset charset, ExporterProfile profile,
            String databaseType, StatementPolicy statementPolicy, DataStatementConsumer statementConsumer,
            ProgressListener progressListener) throws IOException {
        if (statementConsumer == null) {
            throw new IllegalArgumentException("A data statement consumer is required");
        }
        return parse(source, charset, profile, statementPolicy, SqlDialect.resolve(profile, databaseType),
                statementConsumer, progressListener);
    }

    private static Inspection parse(File source, Charset charset, ExporterProfile profile,
            StatementPolicy statementPolicy, DataStatementConsumer statementConsumer,
            ProgressListener progressListener) throws IOException {
        return parse(source, charset, profile, statementPolicy, SqlDialect.resolve(profile, null),
                statementConsumer, progressListener);
    }

    private static Inspection parse(File source, Charset charset, ExporterProfile profile,
            StatementPolicy statementPolicy, SqlDialect dialect, DataStatementConsumer statementConsumer,
            ProgressListener progressListener) throws IOException {
        if (source == null || !source.isFile() || !source.canRead()) {
            throw new IllegalArgumentException("A readable SQL export file is required");
        }
        if (charset == null || profile == null || statementPolicy == null) {
            throw new IllegalArgumentException("SQL export charset, profile, and statement policy are required");
        }
        Limits limits = Limits.configured();
        long sourceBytes = Files.size(source.toPath());
        if (sourceBytes > limits.maxSourceBytes()) {
            throw new IllegalArgumentException("SQL export exceeds " + limits.maxSourceBytes() + " bytes");
        }
        ProgressListener progress = progressListener == null ? (bytes, statements) -> { } : progressListener;
        MessageDigest sourceDigest = sha256Digest();
        try (DigestInputStream digestInput = new DigestInputStream(
                     Files.newInputStream(source.toPath()), sourceDigest);
             CountingInputStream input = new CountingInputStream(digestInput, limits.maxSourceBytes());
             PushbackReader reader = new PushbackReader(new BufferedReader(new InputStreamReader(input,
                     charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT))), 1)) {
            Parser parser = new Parser(profile, dialect, statementPolicy, limits, statementConsumer, progress);
            String physicalLine;
            while (parser.supported()
                    && (physicalLine = readPhysicalLine(reader, limits.maxStatementChars())) != null) {
                parser.acceptPhysicalLine(physicalLine, input.count());
            }
            if (parser.supported()) {
                parser.finish();
            }
            progress.onProgress(input.count(), parser.statementCount());
            Inspection parsed = parser.inspection();
            return new Inspection(parsed.statementCount(), parsed.dataStatementCount(),
                    parsed.filteredStatementCount(), HexFormat.of().formatHex(sourceDigest.digest()),
                    parsed.targetTables(), parsed.unsupported());
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static String readPhysicalLine(PushbackReader reader, long maxChars) throws IOException {
        StringBuilder line = new StringBuilder();
        int current;
        while ((current = reader.read()) != -1) {
            line.append((char) current);
            if (line.length() > maxChars) {
                throw new IllegalArgumentException("SQL physical line exceeds " + maxChars + " characters");
            }
            if (current == '\n') {
                break;
            }
            if (current == '\r') {
                int next = reader.read();
                if (next == '\n') {
                    line.append((char) next);
                } else if (next != -1) {
                    reader.unread(next);
                }
                break;
            }
        }
        return line.isEmpty() && current == -1 ? null : line.toString();
    }

    enum ExporterProfile {
        NAVICAT,
        DBEAVER,
        DATAGRIP,
        HEIDISQL,
        PHPMYADMIN,
        MYSQL_WORKBENCH,
        PGADMIN,
        SSMS,
        ORACLE_SQL_DEVELOPER;

        static ExporterProfile resolve(String value) {
            if (StringUtils.isBlank(value)) {
                return null;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                    .replaceAll("^_+|_+$", "");
            normalized = switch (normalized) {
                case "MYSQLWORKBENCH", "WORKBENCH" -> "MYSQL_WORKBENCH";
                case "PHP_MY_ADMIN" -> "PHPMYADMIN";
                case "ORACLE_SQLDEVELOPER", "SQL_DEVELOPER" -> "ORACLE_SQL_DEVELOPER";
                default -> normalized;
            };
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException unsupported) {
                throw new IllegalArgumentException("Unsupported SQL exporter profile: " + value, unsupported);
            }
        }

    }

    enum StatementPolicy {
        TRUSTED,
        THIRD_PARTY_LITERAL_VALUES
    }

    static void requireCompatibleThirdPartyProfile(ExporterProfile profile, String databaseType) {
        if (profile == null) {
            throw new IllegalArgumentException("THIRD_PARTY SQL imports require an exporter profile");
        }
        DatabaseTypeEnum database = DatabaseTypeEnum.from(databaseType);
        SqlDialect dialect;
        if (database == DatabaseTypeEnum.MYSQL || database == DatabaseTypeEnum.MARIADB) {
            dialect = SqlDialect.MYSQL;
        } else if (database == DatabaseTypeEnum.POSTGRESQL) {
            dialect = SqlDialect.POSTGRESQL;
        } else if (database == DatabaseTypeEnum.SQLSERVER) {
            dialect = SqlDialect.SQLSERVER;
        } else if (database == DatabaseTypeEnum.ORACLE) {
            dialect = SqlDialect.ORACLE;
        } else {
            throw new IllegalArgumentException("THIRD_PARTY SQL import does not support database type: "
                    + StringUtils.defaultIfBlank(databaseType, "UNKNOWN"));
        }

        boolean compatible = switch (profile) {
            case NAVICAT, DBEAVER, DATAGRIP -> true;
            case HEIDISQL -> dialect != SqlDialect.ORACLE;
            case PHPMYADMIN, MYSQL_WORKBENCH -> dialect == SqlDialect.MYSQL;
            case PGADMIN -> dialect == SqlDialect.POSTGRESQL;
            case SSMS -> dialect == SqlDialect.SQLSERVER;
            case ORACLE_SQL_DEVELOPER -> dialect == SqlDialect.ORACLE;
        };
        if (!compatible) {
            throw new IllegalArgumentException("SQL exporter profile " + profile
                    + " is not compatible with database type " + databaseType);
        }
    }

    private enum SqlDialect {
        MYSQL,
        POSTGRESQL,
        SQLSERVER,
        ORACLE,
        GENERIC;

        private static SqlDialect resolve(ExporterProfile profile, String databaseType) {
            DatabaseTypeEnum database = DatabaseTypeEnum.from(databaseType);
            if (database != null) {
                if (database.isMysqlProtocolFamily()) {
                    return MYSQL;
                }
                if (Set.of(DatabaseTypeEnum.POSTGRESQL, DatabaseTypeEnum.KINGBASE, DatabaseTypeEnum.OPENGAUSS,
                        DatabaseTypeEnum.REDSHIFT, DatabaseTypeEnum.COCKROACHDB, DatabaseTypeEnum.GAUSSDB)
                        .contains(database)) {
                    return POSTGRESQL;
                }
                if (database == DatabaseTypeEnum.SQLSERVER) {
                    return SQLSERVER;
                }
                if (Set.of(DatabaseTypeEnum.ORACLE, DatabaseTypeEnum.OSCAR, DatabaseTypeEnum.DM,
                        DatabaseTypeEnum.OCEANBASE_ORACLE).contains(database)) {
                    return ORACLE;
                }
            }
            if (profile == null) {
                return GENERIC;
            }
            return switch (profile) {
                case NAVICAT, HEIDISQL, PHPMYADMIN, MYSQL_WORKBENCH -> MYSQL;
                case PGADMIN -> POSTGRESQL;
                case SSMS -> SQLSERVER;
                case ORACLE_SQL_DEVELOPER -> ORACLE;
                case DBEAVER, DATAGRIP -> GENERIC;
            };
        }

        private DbType druidType() {
            return switch (this) {
                case MYSQL -> DbType.mysql;
                case POSTGRESQL -> DbType.postgresql;
                case SQLSERVER -> DbType.sqlserver;
                case ORACLE -> DbType.oracle;
                case GENERIC -> null;
            };
        }
    }

    record Inspection(int statementCount, int dataStatementCount, int filteredStatementCount,
                      String sourceSha256, List<TargetTable> targetTables, UnsupportedDirective unsupported) {

        boolean supported() {
            return unsupported == null;
        }
    }

    record TargetTable(String catalog, String schema, String table, Set<ColumnReference> columns) {

        TargetTable {
            catalog = normalizeIdentifier(catalog);
            schema = normalizeIdentifier(schema);
            table = normalizeIdentifier(table);
            columns = immutableColumns(columns);
            if (StringUtils.isBlank(table)) {
                throw new IllegalArgumentException("SQL data statement target table is required");
            }
        }

        TargetTable(String catalog, String schema, String table) {
            this(catalog, schema, table, Set.of());
        }

        private static String normalizeIdentifier(String identifier) {
            return StringUtils.isEmpty(identifier) ? null : identifier;
        }

        private static TargetTable parsed(String catalog, String schema, String table, SqlDialect dialect) {
            return new TargetTable(
                    canonicalIdentifier(catalog, dialect),
                    canonicalIdentifier(schema, dialect),
                    canonicalIdentifier(table, dialect), Set.of());
        }

        private TargetTable withColumns(Set<ColumnReference> parsedColumns) {
            return new TargetTable(catalog, schema, table, parsedColumns);
        }

        private TargetTable identity() {
            return columns.isEmpty() ? this : new TargetTable(catalog, schema, table);
        }

        private static String canonicalIdentifier(String identifier, SqlDialect dialect) {
            String raw = StringUtils.trimToNull(identifier);
            if (raw == null) {
                return null;
            }
            boolean quoted = isQuotedIdentifier(raw, dialect);
            String normalized = quoted ? unquoteIdentifier(raw, dialect) : SQLUtils.normalize(raw);
            if (StringUtils.isEmpty(normalized)) {
                return null;
            }
            if (quoted) {
                return normalized;
            }
            return switch (dialect) {
                case POSTGRESQL -> normalized.toLowerCase(Locale.ROOT);
                case ORACLE -> normalized.toUpperCase(Locale.ROOT);
                case MYSQL, SQLSERVER, GENERIC -> normalized;
            };
        }

        private static String unquoteIdentifier(String identifier, SqlDialect dialect) {
            String content = identifier.substring(1, identifier.length() - 1);
            if (identifier.startsWith("\"")) {
                return content.replace("\"\"", "\"");
            }
            if (dialect == SqlDialect.MYSQL && identifier.startsWith("`")) {
                return content.replace("``", "`");
            }
            if (dialect == SqlDialect.SQLSERVER && identifier.startsWith("[")) {
                return content.replace("]]", "]");
            }
            throw new UnsafeDataStatementException("unsupported quoted identifier");
        }

        private static boolean isQuotedIdentifier(String identifier, SqlDialect dialect) {
            return identifier.length() >= 2 && ((identifier.startsWith("\"") && identifier.endsWith("\""))
                    || (dialect == SqlDialect.MYSQL && identifier.startsWith("`") && identifier.endsWith("`"))
                    || (dialect == SqlDialect.SQLSERVER && identifier.startsWith("[")
                    && identifier.endsWith("]")));
        }

        private static Set<ColumnReference> immutableColumns(Set<ColumnReference> columns) {
            if (columns == null || columns.isEmpty()) {
                return Set.of();
            }
            Set<ColumnReference> copied = new LinkedHashSet<>(columns);
            if (copied.contains(null)) {
                throw new IllegalArgumentException("SQL data statement columns cannot contain null");
            }
            return Collections.unmodifiableSet(copied);
        }
    }

    record ColumnReference(String name, boolean quoted) {

        ColumnReference {
            if (StringUtils.isEmpty(name)) {
                throw new IllegalArgumentException("SQL data statement column name is required");
            }
        }

        @Override
        public boolean equals(Object candidate) {
            return candidate instanceof ColumnReference other && name.equals(other.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        private static ColumnReference parsed(String identifier, SqlDialect dialect) {
            String raw = StringUtils.trimToNull(identifier);
            if (raw == null) {
                throw new UnsafeDataStatementException("empty column name");
            }
            boolean quoted = TargetTable.isQuotedIdentifier(raw, dialect);
            return new ColumnReference(TargetTable.canonicalIdentifier(raw, dialect), quoted);
        }
    }

    record UnsupportedDirective(String code, ExporterProfile profile, int line, int statementNumber,
                                String recommendation) {
    }

    @FunctionalInterface
    interface ProgressListener {
        void onProgress(long bytesRead, int statementCount);
    }

    @FunctionalInterface
    interface DataStatementConsumer {
        void accept(String sql, TargetTable targetTable);
    }

    private record Limits(long maxSourceBytes, long maxStatementChars, int maxStatementCount,
                          int maxDistinctTargets) {

        private static Limits configured() {
            return new Limits(
                    configuredLongLimit("chat2db.task.import.sql.max-source-bytes",
                            DEFAULT_MAX_SOURCE_BYTES, HARD_MAX_SOURCE_BYTES),
                    configuredLongLimit("chat2db.task.import.sql.max-statement-chars",
                            DEFAULT_MAX_STATEMENT_CHARS, HARD_MAX_STATEMENT_CHARS),
                    configuredIntLimit("chat2db.task.import.sql.max-statement-count",
                            DEFAULT_MAX_STATEMENT_COUNT, HARD_MAX_STATEMENT_COUNT),
                    configuredIntLimit("chat2db.task.import.sql.max-distinct-targets",
                            DEFAULT_MAX_DISTINCT_TARGETS, HARD_MAX_DISTINCT_TARGETS));
        }

        private static long configuredLongLimit(String property, long defaultValue, long hardLimit) {
            String configured = StringUtils.trimToNull(System.getProperty(property));
            if (configured == null) {
                return defaultValue;
            }
            try {
                long value = Long.parseLong(configured);
                if (value < 1L) {
                    throw new IllegalArgumentException(property + " must be positive");
                }
                return Math.min(value, hardLimit);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(property + " must be a positive integer", invalid);
            }
        }

        private static int configuredIntLimit(String property, int defaultValue, int hardLimit) {
            long value = configuredLongLimit(property, defaultValue, hardLimit);
            return Math.toIntExact(value);
        }
    }

    private enum LexicalState {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        BACKTICK,
        BRACKET_IDENTIFIER,
        DOLLAR_QUOTE,
        ORACLE_Q_QUOTE,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    private static final class Parser {

        private final ExporterProfile profile;
        private final SqlDialect dialect;
        private final StatementPolicy statementPolicy;
        private final DataStatementConsumer statementConsumer;
        private final ProgressListener progressListener;
        private final long maxStatementChars;
        private final int maxStatementCount;
        private final int maxDistinctTargets;
        private StringBuilder statement = new StringBuilder();
        private StringBuilder lexical = new StringBuilder();
        private StringBuilder safetyLexical = new StringBuilder();
        private StringBuilder targetParsingSql = new StringBuilder();
        private boolean safetyLexicalOverflow;
        private boolean targetSanitizationRequired;

        private LexicalState state = LexicalState.NORMAL;
        private String delimiter = ";";
        private String dollarQuoteTag;
        private char oracleQuoteTerminator;
        private boolean escaped;
        private boolean quoteUsesBackslashEscapes;
        private int blockCommentDepth;
        private StringBuilder mysqlExecutableComment;
        private int mysqlExecutableCommentStartLine;
        private int codeStart = -1;
        private int statementStartLine;
        private int lineNumber;
        private int statementCount;
        private int dataStatementCount;
        private int filteredStatementCount;
        private int lastProgressStatementCount;
        private long lastProgressBytes;
        private final Set<TargetTable> targetTables = new LinkedHashSet<>();
        private final Map<TargetTable, Set<ColumnReference>> targetColumns = new LinkedHashMap<>();
        private CopyBlock copyBlock;
        private UnsupportedDirective unsupported;

        private Parser(ExporterProfile profile, SqlDialect dialect, StatementPolicy statementPolicy,
                Limits limits, DataStatementConsumer statementConsumer, ProgressListener progressListener) {
            this.profile = profile;
            this.dialect = dialect;
            this.statementPolicy = statementPolicy;
            this.statementConsumer = statementConsumer;
            this.progressListener = progressListener;
            this.maxStatementChars = limits.maxStatementChars();
            this.maxStatementCount = limits.maxStatementCount();
            this.maxDistinctTargets = limits.maxDistinctTargets();
        }

        private void acceptPhysicalLine(String physicalLine, long bytesRead) {
            lineNumber++;
            String body = withoutLineEnding(physicalLine);
            if (copyBlock != null) {
                acceptCopyDataLine(body);
                reportProgress(bytesRead);
                return;
            }
            if (state == LexicalState.NORMAL) {
                if (handleGoDirective(body) || !supported()) {
                    reportProgress(bytesRead);
                    return;
                }
                if (dialect == SqlDialect.ORACLE && handleOracleSlash(body)) {
                    reportProgress(bytesRead);
                    return;
                }
                if (codeStart < 0 && (handleDelimiterDirective(body) || handleClientCommand(body))) {
                    reportProgress(bytesRead);
                    return;
                }
            }
            scan(physicalLine);
            reportProgress(bytesRead);
        }

        private boolean handleDelimiterDirective(String line) {
            String candidate = stripBom(line);
            Matcher matcher = DELIMITER_DIRECTIVE.matcher(candidate);
            if (!matcher.matches()) {
                if ("DELIMITER".equalsIgnoreCase(firstWord(candidate))) {
                    unsupported = issue("DELIMITER_DIRECTIVE_INVALID", statementCount + 1,
                            "Use one standalone DELIMITER <token> directive in a MySQL-family export.");
                    return true;
                }
                return false;
            }
            if (dialect != SqlDialect.MYSQL) {
                unsupported = issue("DELIMITER_DIRECTIVE", statementCount + 1,
                        "DELIMITER is accepted only for MySQL-family targets; export standard statements for this target.");
                return true;
            }
            String requested = matcher.group(1);
            if (requested.length() > MAX_DELIMITER_CHARS) {
                throw new IllegalArgumentException("SQL delimiter exceeds " + MAX_DELIMITER_CHARS
                        + " characters at line " + lineNumber);
            }
            delimiter = requested;
            clearStatement();
            return true;
        }

        private boolean handleGoDirective(String line) {
            if (dialect != SqlDialect.SQLSERVER) {
                return false;
            }
            String candidate = stripBom(line);
            Matcher matcher = GO_DIRECTIVE.matcher(candidate);
            if (!matcher.matches()) {
                if (GO_LIKE_DIRECTIVE.matcher(candidate).matches()) {
                    unsupported = issue("GO_DIRECTIVE", statementCount + 1,
                            "Export again with a standalone GO separator and no unsupported arguments.");
                    return true;
                }
                return false;
            }
            String repetitions = matcher.group(1);
            if (repetitions != null && !repetitions.matches("0*1")) {
                unsupported = issue("GO_REPEAT", statementCount + 1,
                        "Export again without a repeated or zero GO batch count.");
                return true;
            }
            completeStatement();
            return true;
        }

        private boolean handleOracleSlash(String line) {
            if (!"/".equals(stripBom(line).trim())) {
                return false;
            }
            completeStatement();
            return true;
        }

        private boolean handleClientCommand(String line) {
            String trimmed = stripBom(line).trim();
            if (trimmed.isEmpty()) {
                clearStatement();
                return true;
            }
            if (dialect == SqlDialect.POSTGRESQL && trimmed.startsWith("\\")) {
                String command = firstWord(trimmed).toUpperCase(Locale.ROOT);
                if (SAFE_PSQL_COMMANDS.contains(command) || isSafePsqlSetting(trimmed, command)) {
                    clearStatement();
                    return true;
                }
                String recommendation = "Export the PostgreSQL source with --inserts and without psql meta-commands.";
                unsupported = issue("\\COPY".equals(command) ? "PSQL_COPY" : "PSQL_META_COMMAND",
                        statementCount + 1, recommendation);
                return true;
            }
            if (profile == ExporterProfile.ORACLE_SQL_DEVELOPER) {
                String command = firstWord(trimmed).toUpperCase(Locale.ROOT);
                if (SAFE_SQLCL_COMMANDS.contains(command) && isSafeSqlclCommand(trimmed, command)) {
                    clearStatement();
                    return true;
                }
                if (trimmed.startsWith("@") || UNSAFE_SQLCL_COMMANDS.contains(command)) {
                    unsupported = issue("SQLCL_EXTERNAL_COMMAND", statementCount + 1,
                            "Export one self-contained SQL file without @, START, HOST, GET, RUN, or EDIT commands.");
                    return true;
                }
                if (SAFE_SQLCL_COMMANDS.contains(command)) {
                    unsupported = issue("SQLCL_COMMAND_UNSUPPORTED", statementCount + 1,
                            "Remove SQLcl settings that are not known presentation-only export wrappers.");
                    return true;
                }
            }
            if (profile == ExporterProfile.SSMS && (trimmed.startsWith(":") || trimmed.startsWith("!!"))) {
                unsupported = issue("SQLCMD_COMMAND", statementCount + 1,
                        "Resolve SQLCMD variables and includes before importing the script.");
                return true;
            }
            return false;
        }

        private boolean isSafePsqlSetting(String line, String command) {
            return "\\SET".equals(command)
                    && line.matches("(?i)^\\\\set\\s+ON_ERROR_STOP\\s+(?:on|1|true)\\s*$")
                    || "\\UNSET".equals(command)
                    && line.matches("(?i)^\\\\unset\\s+ON_ERROR_STOP\\s*$");
        }

        private boolean isSafeSqlclCommand(String line, String command) {
            if (!"SET".equals(command)) {
                return true;
            }
            return line.matches("(?i)^SET\\s+(?:DEFINE|SCAN|ESCAPE)\\s+OFF\\s*;?\\s*$")
                    || line.matches("(?i)^SET\\s+SQLBLANKLINES\\s+ON\\s*;?\\s*$")
                    || line.matches("(?i)^SET\\s+SQLFORMAT\\s+(?:INSERT|ANSICONSOLE|DEFAULT)\\s*;?\\s*$");
        }

        private void scan(String input) {
            int index = 0;
            while (index < input.length() && supported()) {
                char current = input.charAt(index);
                if (state == LexicalState.NORMAL) {
                    if (matchesDelimiter(input, index)) {
                        if (dialect == SqlDialect.ORACLE && usesOracleSlashTerminator()
                                || dialect == SqlDialect.SQLSERVER && usesSqlServerGoTerminator()) {
                            appendCode(current);
                            index++;
                        } else {
                            completeStatement();
                            index += delimiter.length();
                        }
                        continue;
                    }
                    if (current == '-' && startsDashComment(input, index)) {
                        appendCommentStart("--");
                        state = LexicalState.LINE_COMMENT;
                        index += 2;
                        continue;
                    }
                    if (current == '#' && dialect == SqlDialect.MYSQL) {
                        appendCommentStart("#");
                        state = LexicalState.LINE_COMMENT;
                        index++;
                        continue;
                    }
                    if (current == '/' && hasNext(input, index, '*')) {
                        appendCommentStart("/*");
                        state = LexicalState.BLOCK_COMMENT;
                        blockCommentDepth = 1;
                        if (dialect == SqlDialect.MYSQL && index + 2 < input.length()
                                && input.charAt(index + 2) == '!') {
                            mysqlExecutableComment = new StringBuilder();
                            mysqlExecutableCommentStartLine = lineNumber;
                        }
                        index += 2;
                        continue;
                    }
                    OracleQuote oracleQuote = dialect == SqlDialect.ORACLE
                            ? oracleQuote(input, index) : null;
                    if (oracleQuote != null) {
                        markCode();
                        String opening = input.substring(index, index + 3);
                        statement.append(opening);
                        appendLexicalSpaces(opening.length());
                        appendTargetLiteral();
                        targetSanitizationRequired = true;
                        checkStatementSize();
                        oracleQuoteTerminator = oracleQuote.terminator();
                        state = LexicalState.ORACLE_Q_QUOTE;
                        index += opening.length();
                        continue;
                    }
                    String tag = current == '$' && dialect == SqlDialect.POSTGRESQL
                            ? dollarQuoteTag(input, index) : null;
                    if (tag != null) {
                        markCode();
                        statement.append(tag);
                        appendLexicalSpaces(tag.length());
                        appendTargetLiteral();
                        targetSanitizationRequired = true;
                        checkStatementSize();
                        dollarQuoteTag = tag;
                        state = LexicalState.DOLLAR_QUOTE;
                        index += tag.length();
                        continue;
                    }
                    LexicalState quoteState = quoteState(current);
                    if (quoteState != null) {
                        markCode();
                        statement.append(current);
                        appendLexical(' ');
                        if (preserveQuotedIdentifier(quoteState)) {
                            appendSafetyCode(current);
                        } else {
                            appendSafetyTrivia();
                        }
                        if (preserveTargetIdentifier(quoteState)) {
                            appendTargetCode(current);
                        } else {
                            appendTargetLiteral(input, index);
                            targetSanitizationRequired = true;
                        }
                        checkStatementSize();
                        state = quoteState;
                        escaped = false;
                        quoteUsesBackslashEscapes = dialect == SqlDialect.MYSQL
                                || isPostgresEscapeString(input, index);
                        index++;
                        continue;
                    }
                    appendCode(current);
                    index++;
                    continue;
                }
                if (state == LexicalState.LINE_COMMENT) {
                    statement.append(current);
                    appendLexicalTrivia(current);
                    checkStatementSize();
                    index++;
                    if (current == '\n' || current == '\r') {
                        state = LexicalState.NORMAL;
                    }
                    continue;
                }
                if (state == LexicalState.BLOCK_COMMENT) {
                    if (current == '/' && hasNext(input, index, '*')) {
                        statement.append("/*");
                        appendLexicalSpaces(2);
                        appendMysqlExecutableComment("/*");
                        blockCommentDepth++;
                        index += 2;
                    } else if (current == '*' && hasNext(input, index, '/')) {
                        statement.append("*/");
                        appendLexicalSpaces(2);
                        blockCommentDepth--;
                        index += 2;
                        if (blockCommentDepth == 0) {
                            state = LexicalState.NORMAL;
                            completeMysqlExecutableComment();
                        }
                    } else {
                        statement.append(current);
                        appendLexicalTrivia(current);
                        appendMysqlExecutableComment(String.valueOf(current));
                        index++;
                    }
                    checkStatementSize();
                    continue;
                }
                if (state == LexicalState.DOLLAR_QUOTE) {
                    if (input.startsWith(dollarQuoteTag, index)) {
                        statement.append(dollarQuoteTag);
                        appendLexicalSpaces(dollarQuoteTag.length());
                        index += dollarQuoteTag.length();
                        dollarQuoteTag = null;
                        state = LexicalState.NORMAL;
                    } else {
                        statement.append(current);
                        appendLexicalTrivia(current);
                        index++;
                    }
                    checkStatementSize();
                    continue;
                }
                if (state == LexicalState.ORACLE_Q_QUOTE) {
                    if (current == oracleQuoteTerminator && hasNext(input, index, '\'')) {
                        statement.append(current).append('\'');
                        appendLexicalSpaces(2);
                        index += 2;
                        oracleQuoteTerminator = 0;
                        state = LexicalState.NORMAL;
                    } else {
                        statement.append(current);
                        appendLexicalTrivia(current);
                        index++;
                    }
                    checkStatementSize();
                    continue;
                }
                if (state == LexicalState.BRACKET_IDENTIFIER) {
                    statement.append(current);
                    appendLexicalTrivia(current);
                    appendTargetCode(current);
                    index++;
                    if (current == ']' && index < input.length() && input.charAt(index) == ']') {
                        statement.append(']');
                        appendLexical(' ');
                        appendTargetCode(']');
                        index++;
                    } else if (current == ']') {
                        state = LexicalState.NORMAL;
                    }
                    checkStatementSize();
                    continue;
                }

                statement.append(current);
                appendQuotedLexical(current);
                if (preserveTargetIdentifier(state)) {
                    appendTargetCode(current);
                }
                index++;
                if (escaped) {
                    escaped = false;
                } else if (quoteUsesBackslashEscapes && current == '\\') {
                    escaped = true;
                } else if (isClosingQuote(current)) {
                    if (index < input.length() && input.charAt(index) == current) {
                        statement.append(current);
                        if (preserveQuotedIdentifier(state)) {
                            appendSafetyCode(current);
                        }
                        appendLexical(' ');
                        if (preserveTargetIdentifier(state)) {
                            appendTargetCode(current);
                        }
                        index++;
                    } else {
                        state = LexicalState.NORMAL;
                    }
                }
                checkStatementSize();
            }
        }

        private void appendMysqlExecutableComment(String value) {
            if (mysqlExecutableComment != null && mysqlExecutableComment.length() <= MAX_LEXICAL_CHARS) {
                mysqlExecutableComment.append(value);
            }
        }

        private void completeMysqlExecutableComment() {
            if (mysqlExecutableComment == null) {
                return;
            }
            String command = mysqlExecutableComment.toString().replaceFirst("(?is)^!\\d{0,6}\\s*", "").trim();
            mysqlExecutableComment = null;
            if (command.isEmpty() || isSafeMysqlExecutableWrapper(command)) {
                return;
            }
            unsupported = issue("MYSQL_EXECUTABLE_COMMENT", mysqlExecutableCommentStartLine,
                    statementCount + 1,
                    "Remove semantics-changing MySQL version comments or export literals independent of SQL mode and time zone.");
        }

        private boolean isSafeMysqlExecutableWrapper(String command) {
            if (command.matches("(?is)^ALTER\\s+TABLE\\b.*\\b(?:DISABLE|ENABLE)\\s+KEYS\\s*;?$")) {
                return true;
            }
            if (SAFE_MYSQL_SESSION.matcher(command.replaceFirst(";\\s*$", "")).matches()) {
                return true;
            }
            if (command.matches("(?is)^SET\\s+@OLD_(CHARACTER_SET_CLIENT|CHARACTER_SET_RESULTS|"
                    + "COLLATION_CONNECTION|FOREIGN_KEY_CHECKS|UNIQUE_CHECKS|AUTOCOMMIT|SQL_NOTES)\\s*=\\s*"
                    + "@@(?:SESSION\\.)?\\1\\s*;?$")) {
                return true;
            }
            if (command.matches("(?is)^SET\\s+(?:@@(?:SESSION\\.)?)?"
                    + "(CHARACTER_SET_CLIENT|CHARACTER_SET_RESULTS|COLLATION_CONNECTION|"
                    + "FOREIGN_KEY_CHECKS|UNIQUE_CHECKS|AUTOCOMMIT|SQL_NOTES)\\s*=\\s*@OLD_\\1\\s*;?$")) {
                return true;
            }
            return command.matches("(?is)^SET\\s+@OLD_(FOREIGN_KEY_CHECKS|UNIQUE_CHECKS|SQL_NOTES)\\s*=\\s*"
                    + "@@(?:SESSION\\.)?\\1\\s*,\\s*\\1\\s*=\\s*[01]\\s*;?$")
                    || command.matches("(?is)^SET\\s+SQL_NOTES\\s*=\\s*[01]\\s*;?$");
        }

        private void finish() {
            if (copyBlock != null) {
                unsupported = issue("COPY_UNTERMINATED", copyBlock.startLine(), statementCount + 1,
                        "Repair the COPY block terminator or export the PostgreSQL source with --inserts.");
                return;
            }
            if (state == LexicalState.LINE_COMMENT) {
                state = LexicalState.NORMAL;
            }
            if (state != LexicalState.NORMAL) {
                throw new IllegalArgumentException("Unterminated " + state.name().toLowerCase(Locale.ROOT)
                        + " in SQL export at line " + lineNumber);
            }
            completeStatement();
        }

        private void completeStatement() {
            if (codeStart < 0) {
                clearStatement();
                return;
            }
            String sql = statement.substring(codeStart).trim();
            String lexicalRaw = lexical.toString();
            String lexicalSql = StringUtils.normalizeSpace(safetyLexical.toString())
                    .toUpperCase(Locale.ROOT);
            String parseableSql = targetParsingSql.toString().trim();
            boolean lexicalOverflow = safetyLexicalOverflow;
            boolean sanitizationRequired = targetSanitizationRequired;
            int startLine = statementStartLine;
            clearStatement();
            if (sql.isEmpty() || lexicalSql.isEmpty()) {
                return;
            }
            incrementStatementCount(startLine);
            if (lexicalOverflow) {
                unsupported = issue("LEXICAL_SCAN_LIMIT", startLine, statementCount,
                        "Split the statement into smaller data-only statements so its full syntax can be verified.");
                return;
            }
            if ("COPY".equals(firstWord(lexicalSql))
                    && startCopyBlock(sql, lexicalRaw, startLine)) {
                return;
            }
            UnsupportedDirective directive = unsupportedDirective(lexicalSql, startLine);
            if (directive != null) {
                unsupported = directive;
                return;
            }
            String keyword = firstWord(lexicalSql);
            if (DATA_STATEMENT_KEYWORDS.contains(keyword)) {
                if (statementPolicy == StatementPolicy.THIRD_PARTY_LITERAL_VALUES
                        && !Set.of("INSERT", "REPLACE", "UPSERT").contains(keyword)) {
                    unsupported = unsafeThirdPartyDml(startLine);
                    return;
                }
                TargetTable target;
                try {
                    target = targetTable(sql, parseableSql, sanitizationRequired, keyword);
                } catch (UnsafeDataStatementException unsafe) {
                    unsupported = unsafeThirdPartyDml(startLine, unsafe.getMessage());
                    return;
                } catch (RuntimeException invalidTarget) {
                    unsupported = issue("DML_TARGET_UNVERIFIED", startLine, statementCount,
                            "Export direct table DML for the selected target; dynamic, multi-target, or unparseable DML is not accepted.");
                    return;
                }
                try {
                    recordTarget(target);
                } catch (UnsafeDataStatementException unsafe) {
                    unsupported = unsafeThirdPartyDml(startLine, unsafe.getMessage());
                    return;
                }
                dataStatementCount++;
                if (statementConsumer != null) {
                    statementConsumer.accept(sql, target);
                }
            } else if (isFilteredWrapper(lexicalSql, keyword)
                    && isSingleCompleteWrapperStatement(sql, lexicalSql, keyword)) {
                filteredStatementCount++;
            } else {
                unsupported = issue("NON_DATA_STATEMENT", startLine, statementCount,
                        "Export data-only INSERT, REPLACE, UPDATE, DELETE, MERGE, or UPSERT statements without client commands.");
            }
        }

        private TargetTable targetTable(String sql, String parseableSql, boolean sanitizationRequired,
                String keyword) {
            DbType dbType = dialect.druidType();
            if (dbType == null) {
                throw new IllegalArgumentException("Cannot verify a DML target for a generic SQL dialect");
            }
            try {
                SQLStatement parsed = SQLUtils.parseSingleStatement(sql, dbType);
                Set<ColumnReference> columns = statementPolicy == StatementPolicy.THIRD_PARTY_LITERAL_VALUES
                        ? validateThirdPartyLiteralStatement(parsed, keyword, sql)
                        : Set.of();
                return parsedTarget(parsed, keyword).withColumns(columns);
            } catch (RuntimeException originalFailure) {
                if (statementPolicy == StatementPolicy.THIRD_PARTY_LITERAL_VALUES
                        || !sanitizationRequired || StringUtils.equals(sql, parseableSql)
                        || StringUtils.isBlank(parseableSql)) {
                    throw originalFailure;
                }
                try {
                    TargetTable sanitizedTarget = parsedTarget(
                            SQLUtils.parseSingleStatement(parseableSql, dbType), keyword);
                    TargetTable rawPrefixTarget = rawPrefixTarget(sql, keyword, dbType);
                    if (!sanitizedTarget.equals(rawPrefixTarget)) {
                        throw new IllegalArgumentException(
                                "Sanitized DML target does not match the original target prefix");
                    }
                    return sanitizedTarget;
                } catch (RuntimeException sanitizedFailure) {
                    sanitizedFailure.addSuppressed(originalFailure);
                    throw sanitizedFailure;
                }
            }
        }

        private TargetTable rawPrefixTarget(String sql, String keyword, DbType dbType) {
            Matcher directTarget = directTarget(sql, keyword);
            if (!directTarget.find()) {
                throw new IllegalArgumentException("The original DML target prefix is not verifiable");
            }
            return parsedTarget(SQLUtils.parseSingleStatement(
                    "DELETE FROM " + directTarget.group(1), dbType), "DELETE");
        }

        private Set<ColumnReference> validateThirdPartyLiteralStatement(
                SQLStatement parsed, String keyword, String sql) {
            if (parsed instanceof SQLInsertStatement insert
                    && "INSERT".equals(keyword)) {
                if (!STRICT_INSERT_VALUES_PREFIX.matcher(sql).find()) {
                    throw new UnsafeDataStatementException("statement is not direct VALUES syntax");
                }
                if (!isExpectedInsertType(insert)) {
                    throw new UnsafeDataStatementException("unexpected AST type " + insert.getClass().getName());
                }
                return validateLiteralInsert(insert);
            }
            throw new UnsafeDataStatementException();
        }

        private boolean isExpectedInsertType(SQLInsertStatement insert) {
            return switch (dialect) {
                case MYSQL -> insert instanceof MySqlInsertStatement;
                case POSTGRESQL -> insert instanceof PGInsertStatement;
                case SQLSERVER -> insert instanceof SQLServerInsertStatement;
                case ORACLE -> insert instanceof OracleInsertStatement;
                case GENERIC -> false;
            };
        }

        private Set<ColumnReference> validateLiteralInsert(SQLInsertStatement insert) {
            if (insert.getQuery() != null || insert.getWith() != null || insert.isOverwrite()
                    || insert.getHint() != null || !isEmpty(insert.getPartitions())
                    || !isEmpty(insert.getHeadHintsDirect())) {
                throw new UnsafeDataStatementException("query, WITH, overwrite, partition, or hint");
            }
            validateDirectTableSource(insert.getTableSource());
            Set<ColumnReference> columns = normalizedRequiredColumns(insert.getColumns());
            validateLiteralValues(insert.getValuesList(), columns.size());

            if (insert instanceof MySqlInsertStatement mysql) {
                if (mysql.isLowPriority() || mysql.isDelayed() || mysql.isHighPriority() || mysql.isIgnore()
                        || mysql.isRollbackOnFail() || mysql.isFulltextDictionary() || mysql.isIfNotExists()
                        || mysql.getHintsSize() > 0 || !isEmpty(mysql.getDuplicateKeyUpdate())) {
                    throw new UnsafeDataStatementException("MySQL modifier, hint, or duplicate-key update");
                }
            }
            if (insert instanceof PGInsertStatement postgres) {
                if (postgres.getReturning() != null || postgres.isDefaultValues()
                        || postgres.getOnConflictWhere() != null || postgres.getOnConflictUpdateWhere() != null
                        || !isDirectNameOrNull(postgres.getOnConflictConstraint())
                        || !isEmpty(postgres.getOnConflictUpdateSetItems())) {
                    throw new UnsafeDataStatementException();
                }
                validateOptionalColumnNames(postgres.getOnConflictTarget());
            }
            if (insert instanceof SQLServerInsertStatement sqlServer
                    && (sqlServer.isDefaultValues() || sqlServer.getOutput() != null || sqlServer.getTop() != null)) {
                throw new UnsafeDataStatementException();
            }
            if (insert instanceof OracleInsertStatement oracle
                    && (oracle.getReturning() != null || oracle.getErrorLogging() != null
                    || !isEmpty(oracle.getHints()))) {
                throw new UnsafeDataStatementException();
            }
            return columns;
        }

        private void validateDirectTableSource(SQLTableSource tableSource) {
            if (!(tableSource instanceof SQLExprTableSource direct)
                    || !(direct.getExpr() instanceof SQLName name) || !isDirectName(name)
                    || StringUtils.isNotBlank(direct.getAlias()) || direct.getHintsSize() > 0
                    || direct.getFlashback() != null || direct.getPivot() != null || direct.getUnpivot() != null
                    || direct.getSampling() != null || direct.getPartitionSize() > 0
                    || !isEmpty(direct.getColumnsDirect())) {
                throw new UnsafeDataStatementException("indirect or extended target table");
            }
        }

        private Set<ColumnReference> normalizedRequiredColumns(List<? extends SQLExpr> columns) {
            if (isEmpty(columns)) {
                throw new UnsafeDataStatementException("explicit column list is required");
            }
            Set<String> normalizedNames = new LinkedHashSet<>();
            Set<ColumnReference> references = new LinkedHashSet<>();
            for (SQLExpr column : columns) {
                if (!(column instanceof SQLIdentifierExpr identifier)) {
                    throw new UnsafeDataStatementException("column name is not a single direct identifier");
                }
                ColumnReference reference = ColumnReference.parsed(identifier.getName(), dialect);
                if (!normalizedNames.add(duplicateColumnKey(reference))) {
                    throw new UnsafeDataStatementException("duplicate column " + reference.name());
                }
                references.add(reference);
            }
            return Collections.unmodifiableSet(references);
        }

        private String duplicateColumnKey(ColumnReference reference) {
            return switch (dialect) {
                case MYSQL, SQLSERVER -> reference.name().toLowerCase(Locale.ROOT);
                case POSTGRESQL, ORACLE, GENERIC -> reference.name();
            };
        }

        private void validateOptionalColumnNames(List<? extends SQLExpr> columns) {
            if (isEmpty(columns)) {
                return;
            }
            for (SQLExpr column : columns) {
                if (!(column instanceof SQLIdentifierExpr)) {
                    throw new UnsafeDataStatementException("column name is not a single direct identifier");
                }
            }
        }

        private void validateLiteralValues(List<ValuesClause> valuesClauses, int expectedArity) {
            if (isEmpty(valuesClauses)) {
                throw new UnsafeDataStatementException("missing VALUES rows");
            }
            for (ValuesClause valuesClause : valuesClauses) {
                if (valuesClause == null || isEmpty(valuesClause.getValues())) {
                    throw new UnsafeDataStatementException("empty VALUES row");
                }
                if (valuesClause.getValues().size() != expectedArity) {
                    throw new UnsafeDataStatementException("VALUES row has "
                            + valuesClause.getValues().size() + " values for " + expectedArity + " columns");
                }
                for (SQLExpr value : valuesClause.getValues()) {
                    if (!isFixedLiteral(value)) {
                        throw new UnsafeDataStatementException("nonliteral VALUES expression "
                                + value.getClass().getSimpleName());
                    }
                }
            }
        }

        private boolean isFixedLiteral(SQLExpr expression) {
            if (!(expression instanceof SQLLiteralExpr) || expression instanceof SQLDefaultExpr
                    || expression.getHint() != null) {
                return false;
            }
            List<SQLObject> children = expression.getChildren();
            if (children == null) {
                return true;
            }
            for (SQLObject child : children) {
                if (!(child instanceof SQLExpr childExpression) || !isFixedLiteral(childExpression)) {
                    return false;
                }
            }
            return true;
        }

        private boolean isDirectNameOrNull(SQLName name) {
            return name == null || isDirectName(name);
        }

        private boolean isDirectName(SQLName name) {
            if (name instanceof SQLIdentifierExpr) {
                return true;
            }
            return name instanceof SQLPropertyExpr property
                    && property.getOwner() instanceof SQLName owner && isDirectName(owner);
        }

        private List<String> directNameParts(SQLName name) {
            List<String> parts = new ArrayList<>();
            appendDirectNameParts(name, parts);
            if (parts.isEmpty() || parts.size() > 3) {
                throw new IllegalArgumentException("Only local one-, two-, or three-part targets are supported");
            }
            return parts;
        }

        private void appendDirectNameParts(SQLName name, List<String> parts) {
            if (name instanceof SQLIdentifierExpr identifier) {
                parts.add(identifier.getName());
                return;
            }
            if (name instanceof SQLPropertyExpr property && property.getOwner() instanceof SQLName owner) {
                appendDirectNameParts(owner, parts);
                parts.add(property.getName());
                return;
            }
            throw new IllegalArgumentException("Only direct local SQL identifiers are supported");
        }

        private boolean isEmpty(List<?> values) {
            return values == null || values.isEmpty();
        }

        private TargetTable parsedTarget(SQLStatement parsed, String keyword) {
            SQLTableSource tableSource;
            if (parsed instanceof SQLInsertStatement insert
                    && ("INSERT".equals(keyword) || "UPSERT".equals(keyword))) {
                tableSource = insert.getTableSource();
            } else if (parsed instanceof SQLReplaceStatement replace && "REPLACE".equals(keyword)) {
                tableSource = replace.getTableSource();
            } else if (parsed instanceof SQLUpdateStatement update && "UPDATE".equals(keyword)) {
                tableSource = update.getTableSource();
            } else if (parsed instanceof SQLDeleteStatement delete && "DELETE".equals(keyword)) {
                tableSource = delete.getExprTableSource();
            } else if (parsed instanceof SQLMergeStatement merge && "MERGE".equals(keyword)) {
                tableSource = merge.getInto();
            } else {
                throw new IllegalArgumentException("Unexpected DML syntax");
            }
            if (!(tableSource instanceof SQLExprTableSource target)) {
                throw new IllegalArgumentException("Only one direct target table is supported");
            }
            List<String> parts = directNameParts(target.getName());
            TargetTable result = switch (parts.size()) {
                case 1 -> TargetTable.parsed(null, null, parts.get(0), dialect);
                case 2 -> TargetTable.parsed(null, parts.get(0), parts.get(1), dialect);
                case 3 -> TargetTable.parsed(parts.get(0), parts.get(1), parts.get(2), dialect);
                default -> throw new IllegalArgumentException("Unexpected SQL target identifier");
            };
            if (result.table().contains("@") || StringUtils.contains(result.schema(), "@")
                    || StringUtils.contains(result.catalog(), "@")) {
                throw new IllegalArgumentException("Remote target tables are not supported");
            }
            return result;
        }

        private Matcher directTarget(String sql, String keyword) {
            Pattern pattern = switch (keyword) {
                case "INSERT", "UPSERT" -> DIRECT_INSERT_TARGET;
                case "REPLACE" -> DIRECT_REPLACE_TARGET;
                case "UPDATE" -> DIRECT_UPDATE_TARGET;
                case "DELETE" -> DIRECT_DELETE_TARGET;
                case "MERGE" -> DIRECT_MERGE_TARGET;
                default -> throw new IllegalArgumentException("Unexpected DML keyword: " + keyword);
            };
            return pattern.matcher(sql);
        }

        private boolean isFilteredWrapper(String sql, String keyword) {
            if (STRUCTURAL_STATEMENT_KEYWORDS.contains(keyword)) {
                return !("ALTER".equals(keyword) && sql.matches("(?is)^ALTER\\s+SESSION\\b.*"));
            }
            if ("COMMIT".equals(keyword)) {
                return sql.matches("(?is)^COMMIT(?:\\s+WORK)?$");
            }
            if ("START".equals(keyword)) {
                return sql.matches("(?is)^START\\s+TRANSACTION$");
            }
            if ("BEGIN".equals(keyword)) {
                return dialect != SqlDialect.ORACLE
                        && sql.matches("(?is)^BEGIN(?:\\s+(?:WORK|TRANSACTION))?$");
            }
            if (dialect == SqlDialect.MYSQL && SAFE_MYSQL_SESSION.matcher(sql).matches()) {
                return true;
            }
            if (dialect == SqlDialect.POSTGRESQL && SAFE_POSTGRES_SESSION.matcher(sql).matches()) {
                return true;
            }
            return dialect == SqlDialect.SQLSERVER && SAFE_SQLSERVER_SESSION.matcher(sql).matches();
        }

        private boolean isSingleCompleteWrapperStatement(String sql, String lexicalSql, String keyword) {
            // A SQL Server module definition owns the rest of its GO batch, including its body DML.
            if (dialect != SqlDialect.SQLSERVER || !STRUCTURAL_STATEMENT_KEYWORDS.contains(keyword)
                    || SQLSERVER_GO_BLOCK.matcher(lexicalSql).find()) {
                return true;
            }
            try {
                SQLUtils.parseSingleStatement(sql, dialect.druidType());
                return true;
            } catch (RuntimeException unverified) {
                return false;
            }
        }

        private boolean startCopyBlock(String sql, String lexicalSql, int startLine) {
            if (dialect != SqlDialect.POSTGRESQL) {
                return false;
            }
            Matcher fromStdin = FROM_STDIN_CLAUSE.matcher(lexicalSql);
            if (!fromStdin.find()) {
                return false;
            }
            try {
                String options = sql.substring(fromStdin.end()).trim();
                if (!options.isEmpty()) {
                    throw copyFailure("COPY_OPTIONS",
                            "Only default pg_dump text COPY is supported; export option-bearing COPY with --inserts.");
                }
                String targetClause = sql.substring("COPY".length(), fromStdin.start()).trim();
                copyBlock = parseCopyTarget(targetClause, startLine);
                recordTarget(copyBlock.targetTable());
                filteredStatementCount++;
            } catch (IndexOutOfBoundsException invalidBoundary) {
                unsupported = issue("COPY_HEADER", startLine, statementCount,
                        "Export a standard pg_dump text COPY block or use --inserts.");
            } catch (CopyFormatException invalidCopy) {
                unsupported = issue(invalidCopy.code(), startLine, statementCount,
                        invalidCopy.recommendation());
            } catch (UnsafeDataStatementException unsafe) {
                unsupported = unsafeThirdPartyDml(startLine, unsafe.getMessage());
            }
            return true;
        }

        private CopyBlock parseCopyTarget(String targetClause, int startLine) throws CopyFormatException {
            int columnsStart = findUnquoted(targetClause, '(', 0);
            String relation;
            List<String> columns;
            if (columnsStart < 0) {
                throw copyFailure("COPY_COLUMNS",
                        "COPY conversion requires an explicit column list; export with pg_dump --inserts if unavailable.");
            } else {
                int columnsEnd = findUnquoted(targetClause, ')', columnsStart + 1);
                if (columnsEnd < 0 || !targetClause.substring(columnsEnd + 1).trim().isEmpty()
                        || findUnquoted(targetClause, '(', columnsStart + 1) >= 0) {
                    throw copyFailure("COPY_COLUMNS",
                            "Export a standard COPY column list or use PostgreSQL --inserts.");
                }
                relation = targetClause.substring(0, columnsStart).trim();
                columns = splitCopyColumns(targetClause.substring(columnsStart + 1, columnsEnd));
            }
            if (!POSTGRES_RELATION.matcher(relation).matches()) {
                throw copyFailure("COPY_TARGET",
                        "Export COPY for one schema-qualified table or use PostgreSQL --inserts.");
            }
            int separator = findUnquoted(relation, '.', 0);
            TargetTable target = separator < 0
                    ? TargetTable.parsed(null, null, relation, SqlDialect.POSTGRESQL)
                    : TargetTable.parsed(null, relation.substring(0, separator),
                            relation.substring(separator + 1), SqlDialect.POSTGRESQL);
            if (statementPolicy == StatementPolicy.THIRD_PARTY_LITERAL_VALUES) {
                target = target.withColumns(normalizedCopyColumns(columns));
            }
            return new CopyBlock(relation, columns, startLine, target);
        }

        private Set<ColumnReference> normalizedCopyColumns(List<String> columns) throws CopyFormatException {
            Set<String> normalizedNames = new LinkedHashSet<>();
            Set<ColumnReference> references = new LinkedHashSet<>();
            for (String column : columns) {
                ColumnReference reference = ColumnReference.parsed(column, SqlDialect.POSTGRESQL);
                if (!normalizedNames.add(reference.name())) {
                    throw copyFailure("COPY_COLUMNS",
                            "COPY column names must be unique after PostgreSQL identifier normalization.");
                }
                references.add(reference);
            }
            return Collections.unmodifiableSet(references);
        }

        private List<String> splitCopyColumns(String value) throws CopyFormatException {
            List<String> columns = new ArrayList<>();
            boolean quoted = false;
            int start = 0;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '"') {
                    if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '"') {
                        index++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (current == ',' && !quoted) {
                    columns.add(validatedCopyColumn(value.substring(start, index)));
                    start = index + 1;
                }
            }
            if (quoted) {
                throw copyFailure("COPY_COLUMNS",
                        "Repair the quoted COPY column list or export PostgreSQL data with --inserts.");
            }
            columns.add(validatedCopyColumn(value.substring(start)));
            return List.copyOf(columns);
        }

        private String validatedCopyColumn(String value) throws CopyFormatException {
            String column = value.trim();
            if (!POSTGRES_COLUMN.matcher(column).matches()) {
                throw copyFailure("COPY_COLUMNS",
                        "Export a standard COPY column list or use PostgreSQL --inserts.");
            }
            return column;
        }

        private int findUnquoted(String value, char expected, int start) throws CopyFormatException {
            boolean quoted = false;
            for (int index = start; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '"') {
                    if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '"') {
                        index++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (current == expected && !quoted) {
                    return index;
                }
            }
            if (quoted) {
                throw copyFailure("COPY_TARGET",
                        "Repair the quoted COPY target or export PostgreSQL data with --inserts.");
            }
            return -1;
        }

        private void acceptCopyDataLine(String line) {
            if ("\\.".equals(line)) {
                copyBlock = null;
                return;
            }
            if (line.length() > maxStatementChars) {
                unsupported = issue("COPY_ROW_TOO_LARGE", statementCount + 1,
                        "Reduce the exported row size or export PostgreSQL data with --inserts.");
                return;
            }
            List<String> fields = splitCopyFields(line);
            int expectedColumns = copyBlock.expectedColumnCount();
            if (expectedColumns < 0) {
                copyBlock.expectedColumnCount(fields.size());
            } else if (fields.size() != expectedColumns) {
                unsupported = issue("COPY_COLUMN_COUNT", statementCount + 1,
                        "COPY row column count does not match its header; repair the dump or export with --inserts.");
                return;
            }
            try {
                StringBuilder insert = new StringBuilder(copyBlock.insertPrefix());
                for (int index = 0; index < fields.size(); index++) {
                    if (index > 0) {
                        insert.append(", ");
                    }
                    insert.append(copyFieldLiteral(fields.get(index)));
                }
                insert.append(')');
                if (insert.length() > maxStatementChars) {
                    throw copyFailure("COPY_ROW_TOO_LARGE",
                            "Reduce the exported row size or export PostgreSQL data with --inserts.");
                }
                incrementStatementCount(copyBlock.startLine());
                dataStatementCount++;
                if (statementConsumer != null) {
                    statementConsumer.accept(insert.toString(), copyBlock.targetTable());
                }
            } catch (CopyFormatException invalidRow) {
                unsupported = issue(invalidRow.code(), statementCount + 1,
                        invalidRow.recommendation());
            }
        }

        private List<String> splitCopyFields(String line) {
            List<String> fields = new ArrayList<>();
            int start = 0;
            for (int index = 0; index < line.length(); index++) {
                if (line.charAt(index) == '\t') {
                    fields.add(line.substring(start, index));
                    start = index + 1;
                }
            }
            fields.add(line.substring(start));
            return fields;
        }

        private String copyFieldLiteral(String encoded) throws CopyFormatException {
            if ("\\N".equals(encoded)) {
                return "NULL";
            }
            StringBuilder literal = new StringBuilder(encoded.length() + 3).append("E'");
            for (int index = 0; index < encoded.length(); index++) {
                char current = encoded.charAt(index);
                if (current != '\\') {
                    appendPostgresLiteralCharacter(literal, current);
                    continue;
                }
                if (++index >= encoded.length()) {
                    throw copyFailure("COPY_ESCAPE",
                            "Repair the trailing COPY escape or export PostgreSQL data with --inserts.");
                }
                char escapedValue = encoded.charAt(index);
                switch (escapedValue) {
                    case 'b' -> appendPostgresLiteralCharacter(literal, '\b');
                    case 'f' -> appendPostgresLiteralCharacter(literal, '\f');
                    case 'n' -> appendPostgresLiteralCharacter(literal, '\n');
                    case 'r' -> appendPostgresLiteralCharacter(literal, '\r');
                    case 't' -> appendPostgresLiteralCharacter(literal, '\t');
                    case 'v' -> appendPostgresLiteralCharacter(literal, '\u000B');
                    case '\\' -> appendPostgresLiteralCharacter(literal, '\\');
                    case 'x' -> {
                        int value = 0;
                        int digits = 0;
                        int digitsStart = index + 1;
                        while (digits < 2 && index + 1 < encoded.length()
                                && hexValue(encoded.charAt(index + 1)) >= 0) {
                            value = value * 16 + hexValue(encoded.charAt(++index));
                            digits++;
                        }
                        if (digits == 0) {
                            throw copyFailure("COPY_ESCAPE",
                                    "Repair the hexadecimal COPY escape or export PostgreSQL data with --inserts.");
                        }
                        rejectNulCopyByte(value);
                        literal.append("\\x").append(encoded, digitsStart, digitsStart + digits);
                    }
                    default -> {
                        if (escapedValue >= '0' && escapedValue <= '7') {
                            int value = escapedValue - '0';
                            int digits = 1;
                            int digitsStart = index;
                            while (digits < 3 && index + 1 < encoded.length()
                                    && encoded.charAt(index + 1) >= '0' && encoded.charAt(index + 1) <= '7') {
                                value = value * 8 + encoded.charAt(++index) - '0';
                                digits++;
                            }
                            if (value > 0xFF) {
                                throw copyFailure("COPY_ESCAPE",
                                        "COPY octal escapes must represent one byte; repair the dump or export with --inserts.");
                            }
                            rejectNulCopyByte(value);
                            literal.append('\\').append(encoded, digitsStart, digitsStart + digits);
                        } else {
                            appendPostgresLiteralCharacter(literal, escapedValue);
                        }
                    }
                }
            }
            return literal.append('\'').toString();
        }

        private void rejectNulCopyByte(int value) throws CopyFormatException {
            if (value == 0) {
                throw copyFailure("COPY_NUL",
                        "PostgreSQL text values cannot contain NUL; repair the dump or export with --inserts.");
            }
        }

        private void appendPostgresLiteralCharacter(StringBuilder literal, char value) {
            if (value == '\'') {
                literal.append("''");
            } else if (value == '\\') {
                literal.append("\\\\");
            } else if (value < 0x20 || value == 0x7F) {
                literal.append('\\')
                        .append((char) ('0' + (value >> 6 & 7)))
                        .append((char) ('0' + (value >> 3 & 7)))
                        .append((char) ('0' + (value & 7)));
            } else {
                literal.append(value);
            }
        }

        private int hexValue(char value) {
            if (value >= '0' && value <= '9') {
                return value - '0';
            }
            if (value >= 'a' && value <= 'f') {
                return value - 'a' + 10;
            }
            if (value >= 'A' && value <= 'F') {
                return value - 'A' + 10;
            }
            return -1;
        }

        private CopyFormatException copyFailure(String code, String recommendation) {
            return new CopyFormatException(code, recommendation);
        }

        private UnsupportedDirective unsupportedDirective(String sql, int startLine) {
            String keyword = firstWord(sql);
            if (INSERT_EXEC.matcher(sql).find()) {
                return issue("SQLSERVER_INSERT_EXEC", startLine, statementCount,
                        "Generate literal INSERT values without INSERT EXEC or stored procedure execution.");
            }
            if (dialect == SqlDialect.SQLSERVER && SQLSERVER_OUTPUT_INTO.matcher(sql).find()) {
                return issue("SQLSERVER_SECONDARY_TARGET", startLine, statementCount,
                        "Export direct table DML without OUTPUT INTO writing to a second target.");
            }
            if (UPDATE_STATISTICS.matcher(sql).find()) {
                return issue("UPDATE_STATISTICS", startLine, statementCount,
                        "Run statistics maintenance separately after the data import is verified.");
            }
            int number = statementCount;
            if ("COPY".equals(keyword)) {
                String code = COPY_FROM_STDIN.matcher(sql).find() ? "COPY_FROM_STDIN" : "COPY";
                return issue(code, startLine, number,
                        "Export the PostgreSQL source with --inserts instead of COPY.");
            }
            if ("LOAD".equals(keyword)) {
                return issue("LOAD_DATA", startLine, number,
                        "Export inline INSERT or REPLACE statements instead of LOAD DATA/LOAD XML.");
            }
            if ("BULK".equals(keyword) || OPENROWSET_BULK.matcher(sql).find()) {
                return issue("SQLSERVER_BULK", startLine, number,
                        "Generate inline INSERT statements instead of BULK INSERT or OPENROWSET(BULK...).");
            }
            if (IDENTITY_INSERT.matcher(sql).find()) {
                return issue("IDENTITY_INSERT", startLine, number,
                        "Regenerate the SQL without preserving identity values; SET IDENTITY_INSERT cannot be replayed safely.");
            }
            if ("SOURCE".equals(keyword)) {
                return issue("EXTERNAL_SOURCE", startLine, number,
                        "Resolve SOURCE includes into one self-contained SQL export before importing.");
            }
            if (dialect == SqlDialect.POSTGRESQL && POSTGRES_SETVAL.matcher(sql).find()) {
                return issue("POSTGRES_SEQUENCE_SETVAL", startLine, number,
                        "Reset PostgreSQL sequences with the verified post-import finalization step; setval is not rollback-safe.");
            }
            if (dialect == SqlDialect.POSTGRESQL && POSTGRES_LARGE_OBJECT.matcher(sql).find()) {
                return issue("POSTGRES_LARGE_OBJECT", startLine, number,
                        "Export large objects through a dedicated binary-safe migration path; they cannot be silently removed.");
            }
            if ("TRUNCATE".equals(keyword)) {
                return issue("DATA_DESTRUCTIVE_DDL", startLine, number,
                        "Clear the selected target explicitly before import; TRUNCATE cannot be stripped from a data script.");
            }
            if (Set.of("ABORT", "RELEASE", "ROLLBACK", "SAVEPOINT").contains(keyword)
                    || Set.of("BEGIN", "COMMIT", "START").contains(keyword)
                    && dialect != SqlDialect.ORACLE && !isFilteredWrapper(sql, keyword)) {
                return issue("TRANSACTION_CONTROL", startLine, number,
                        "Export only a plain BEGIN/START TRANSACTION and COMMIT wrapper; rollback and savepoint semantics cannot be stripped.");
            }
            if ("SET".equals(keyword) && !isFilteredWrapper(sql, keyword)) {
                return issue("SESSION_DIRECTIVE", startLine, number,
                        "Remove semantics-changing session settings or export literals independent of time zone, locale, and SQL mode.");
            }
            if ("USE".equals(keyword) || "PRAGMA".equals(keyword)
                    || "ALTER".equals(keyword) && sql.matches("(?is)^ALTER\\s+SESSION\\b.*")) {
                return issue("SESSION_DIRECTIVE", startLine, number,
                        "Select the destination in Chat2DB and remove source database or session switching directives.");
            }
            if (Set.of("CALL", "DECLARE", "DO", "EXEC", "EXECUTE").contains(keyword)
                    || "BEGIN".equals(keyword) && dialect == SqlDialect.ORACLE) {
                return issue("PROCEDURAL_STATEMENT", startLine, number,
                        "Export direct data statements rather than procedural blocks or procedure calls.");
            }
            return null;
        }

        private UnsupportedDirective issue(String code, int number, String recommendation) {
            return issue(code, lineNumber, number, recommendation);
        }

        private UnsupportedDirective issue(String code, int line, int number, String recommendation) {
            return new UnsupportedDirective(code, profile, line, number, recommendation);
        }

        private boolean usesOracleSlashTerminator() {
            return ORACLE_BLOCK.matcher(StringUtils.normalizeSpace(lexical.toString())).find();
        }

        private boolean usesSqlServerGoTerminator() {
            return SQLSERVER_GO_BLOCK.matcher(StringUtils.normalizeSpace(lexical.toString())).find();
        }

        private boolean matchesDelimiter(String input, int index) {
            return !delimiter.isEmpty() && input.startsWith(delimiter, index);
        }

        private void appendCode(char value) {
            if (codeStart < 0 && value != '\uFEFF' && !Character.isWhitespace(value)) {
                markCode();
            }
            statement.append(value);
            if (codeStart >= 0) {
                appendLexical(value == '\uFEFF' ? ' ' : value);
                if (value == '\uFEFF') {
                    appendSafetyTrivia();
                    appendTargetTrivia();
                } else if (Character.isWhitespace(value)) {
                    appendSafetyTrivia();
                    appendTargetTrivia();
                } else {
                    appendSafetyCode(value);
                    appendTargetCode(value);
                }
            }
            checkStatementSize();
        }

        private void appendCommentStart(String value) {
            statement.append(value);
            if (codeStart >= 0) {
                appendLexicalSpaces(value.length());
                appendTargetTrivia();
            }
            checkStatementSize();
        }

        private void appendLexicalTrivia(char value) {
            if (codeStart >= 0) {
                appendLexical(value == '\n' || value == '\r' ? value : ' ');
                appendSafetyTrivia();
            }
        }

        private void appendLexicalSpaces(int count) {
            int remaining = MAX_LEXICAL_CHARS - lexical.length();
            if (remaining > 0) {
                lexical.append(" ".repeat(Math.min(count, remaining)));
            }
            if (codeStart >= 0) {
                appendSafetyTrivia();
            }
        }

        private void appendLexical(char value) {
            if (lexical.length() < MAX_LEXICAL_CHARS) {
                lexical.append(value);
            }
        }

        private void appendQuotedLexical(char value) {
            if (preserveQuotedIdentifier(state)) {
                appendLexical(' ');
                appendSafetyCode(value);
            } else {
                appendLexicalTrivia(value);
            }
        }

        private boolean preserveQuotedIdentifier(LexicalState quoteState) {
            return dialect == SqlDialect.POSTGRESQL && quoteState == LexicalState.DOUBLE_QUOTE;
        }

        private boolean preserveTargetIdentifier(LexicalState quoteState) {
            return quoteState == LexicalState.BACKTICK
                    || quoteState == LexicalState.BRACKET_IDENTIFIER
                    || quoteState == LexicalState.DOUBLE_QUOTE && dialect != SqlDialect.MYSQL;
        }

        private void appendSafetyCode(char value) {
            if (safetyLexical.length() < MAX_LEXICAL_CHARS) {
                safetyLexical.append(value);
            } else {
                safetyLexicalOverflow = true;
            }
        }

        private void appendSafetyTrivia() {
            if (safetyLexical.isEmpty()
                    || Character.isWhitespace(safetyLexical.charAt(safetyLexical.length() - 1))) {
                return;
            }
            if (safetyLexical.length() < MAX_LEXICAL_CHARS) {
                safetyLexical.append(' ');
            }
        }

        private void appendTargetLiteral(String input, int quoteIndex) {
            int prefixLength = literalPrefixLength(input, quoteIndex);
            if (prefixLength > 0) {
                targetSanitizationRequired = true;
            }
            while (prefixLength > 0 && !targetParsingSql.isEmpty()) {
                targetParsingSql.setLength(targetParsingSql.length() - 1);
                prefixLength--;
            }
            appendTargetLiteral();
        }

        private int literalPrefixLength(String input, int quoteIndex) {
            if (quoteIndex >= 2 && input.charAt(quoteIndex - 1) == '&'
                    && (input.charAt(quoteIndex - 2) == 'U' || input.charAt(quoteIndex - 2) == 'u')
                    && isTokenBoundary(input, quoteIndex - 3)) {
                return 2;
            }
            if (quoteIndex >= 1 && "EeNnBbXx".indexOf(input.charAt(quoteIndex - 1)) >= 0
                    && isTokenBoundary(input, quoteIndex - 2)) {
                return 1;
            }
            return 0;
        }

        private boolean isTokenBoundary(String input, int index) {
            return index < 0 || !Character.isLetterOrDigit(input.charAt(index))
                    && input.charAt(index) != '_' && input.charAt(index) != '$';
        }

        private void appendTargetLiteral() {
            appendTargetText("NULL");
        }

        private void appendTargetCode(char value) {
            if (targetParsingSql.length() < MAX_LEXICAL_CHARS) {
                targetParsingSql.append(value);
            } else {
                safetyLexicalOverflow = true;
            }
        }

        private void appendTargetText(String value) {
            for (int index = 0; index < value.length(); index++) {
                appendTargetCode(value.charAt(index));
            }
        }

        private void appendTargetTrivia() {
            if (targetParsingSql.isEmpty()
                    || Character.isWhitespace(targetParsingSql.charAt(targetParsingSql.length() - 1))) {
                return;
            }
            appendTargetCode(' ');
        }

        private void markCode() {
            if (codeStart < 0) {
                codeStart = statement.length();
                statementStartLine = lineNumber;
            }
        }

        private void checkStatementSize() {
            if (statement.length() > maxStatementChars) {
                throw new IllegalArgumentException("SQL statement beginning at line " + statementStartLine
                        + " exceeds " + maxStatementChars + " characters");
            }
        }

        private void incrementStatementCount(int startLine) {
            if (statementCount >= maxStatementCount) {
                throw new IllegalArgumentException("SQL export exceeds " + maxStatementCount
                        + " statements at line " + startLine);
            }
            statementCount++;
        }

        private void recordTarget(TargetTable target) {
            TargetTable identity = target.identity();
            Set<ColumnReference> existingColumns = targetColumns.get(identity);
            if (existingColumns != null && !existingColumns.equals(target.columns())) {
                throw new UnsafeDataStatementException("inconsistent explicit column set for target "
                        + String.join(".", java.util.stream.Stream.of(
                                identity.catalog(), identity.schema(), identity.table())
                        .filter(StringUtils::isNotBlank).toList()));
            }
            if (existingColumns != null) {
                return;
            }
            if (targetColumns.size() >= maxDistinctTargets) {
                throw new IllegalArgumentException("SQL export exceeds " + maxDistinctTargets
                        + " distinct target tables");
            }
            targetColumns.put(identity, target.columns());
            targetTables.add(target);
        }

        private UnsupportedDirective unsafeThirdPartyDml(int startLine) {
            return unsafeThirdPartyDml(startLine, null);
        }

        private UnsupportedDirective unsafeThirdPartyDml(int startLine, String detail) {
            return issue("THIRD_PARTY_DML_UNSAFE", startLine, statementCount,
                    "Export literal VALUES-only INSERT statements without queries, updates, functions, sequences, defaults, or computed expressions."
                            + (detail == null ? "" : " Rejected construct: " + detail));
        }

        private void clearStatement() {
            statement = clearBuffer(statement);
            lexical = clearBuffer(lexical);
            safetyLexical = clearBuffer(safetyLexical);
            targetParsingSql = clearBuffer(targetParsingSql);
            safetyLexicalOverflow = false;
            targetSanitizationRequired = false;
            codeStart = -1;
            statementStartLine = 0;
            escaped = false;
            quoteUsesBackslashEscapes = false;
        }

        private StringBuilder clearBuffer(StringBuilder buffer) {
            if (buffer.capacity() > RETAINED_BUFFER_CHARS) {
                return new StringBuilder();
            }
            buffer.setLength(0);
            return buffer;
        }

        private boolean supported() {
            return unsupported == null;
        }

        private int statementCount() {
            return statementCount;
        }

        private Inspection inspection() {
            return new Inspection(statementCount, dataStatementCount, filteredStatementCount, null,
                    List.copyOf(targetTables), unsupported);
        }

        private void reportProgress(long bytesRead) {
            boolean statementBoundary = statementCount > 0 && statementCount % PROGRESS_INTERVAL == 0
                    && statementCount != lastProgressStatementCount;
            if (statementBoundary || bytesRead - lastProgressBytes >= PROGRESS_BYTE_INTERVAL) {
                lastProgressStatementCount = statementCount;
                lastProgressBytes = bytesRead;
                progressListener.onProgress(bytesRead, statementCount);
            }
        }

        private boolean isClosingQuote(char value) {
            return state == LexicalState.SINGLE_QUOTE && value == '\''
                    || state == LexicalState.DOUBLE_QUOTE && value == '"'
                    || state == LexicalState.BACKTICK && value == '`';
        }

        private LexicalState quoteState(char value) {
            return switch (value) {
                case '\'' -> LexicalState.SINGLE_QUOTE;
                case '"' -> LexicalState.DOUBLE_QUOTE;
                case '`' -> LexicalState.BACKTICK;
                case '[' -> dialect == SqlDialect.SQLSERVER ? LexicalState.BRACKET_IDENTIFIER : null;
                default -> null;
            };
        }

        private boolean startsDashComment(String input, int index) {
            if (!hasNext(input, index, '-')) {
                return false;
            }
            if (dialect != SqlDialect.MYSQL) {
                return true;
            }
            int following = index + 2;
            return following >= input.length() || Character.isWhitespace(input.charAt(following));
        }

        private boolean isPostgresEscapeString(String input, int quoteIndex) {
            if (dialect != SqlDialect.POSTGRESQL || quoteIndex == 0) {
                return false;
            }
            char prefix = input.charAt(quoteIndex - 1);
            if (prefix != 'E' && prefix != 'e') {
                return false;
            }
            return quoteIndex == 1 || !Character.isLetterOrDigit(input.charAt(quoteIndex - 2))
                    && input.charAt(quoteIndex - 2) != '_';
        }

        private OracleQuote oracleQuote(String input, int index) {
            if (index + 2 >= input.length() || input.charAt(index + 1) != '\''
                    || input.charAt(index) != 'q' && input.charAt(index) != 'Q') {
                return null;
            }
            char opening = input.charAt(index + 2);
            if (Character.isWhitespace(opening) || opening == '\'' || opening == '\\') {
                return null;
            }
            char terminator = switch (opening) {
                case '[' -> ']';
                case '{' -> '}';
                case '(' -> ')';
                case '<' -> '>';
                default -> opening;
            };
            return new OracleQuote(terminator);
        }

        private boolean hasNext(String input, int index, char expected) {
            return index + 1 < input.length() && input.charAt(index + 1) == expected;
        }

        private String dollarQuoteTag(String input, int index) {
            if (index > 0 && isIdentifierPart(input.charAt(index - 1))) {
                return null;
            }
            int end = input.indexOf('$', index + 1);
            if (end < 0) {
                return null;
            }
            String tagBody = input.substring(index + 1, end);
            if (!tagBody.isEmpty() && !tagBody.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return null;
            }
            return input.substring(index, end + 1);
        }

        private boolean isIdentifierPart(char value) {
            return Character.isLetterOrDigit(value) || value == '_' || value == '$';
        }
    }

    private static final class CopyBlock {

        private final String relation;
        private final List<String> columns;
        private final int startLine;
        private final TargetTable targetTable;
        private int expectedColumnCount;

        private CopyBlock(String relation, List<String> columns, int startLine, TargetTable targetTable) {
            this.relation = relation;
            this.columns = columns;
            this.startLine = startLine;
            this.targetTable = targetTable;
            this.expectedColumnCount = columns.isEmpty() ? -1 : columns.size();
        }

        private String insertPrefix() {
            String columnClause = columns.isEmpty() ? "" : " (" + String.join(", ", columns) + ")";
            return "INSERT INTO " + relation + columnClause + " VALUES (";
        }

        private int startLine() {
            return startLine;
        }

        private TargetTable targetTable() {
            return targetTable;
        }

        private int expectedColumnCount() {
            return expectedColumnCount;
        }

        private void expectedColumnCount(int value) {
            expectedColumnCount = value;
        }
    }

    private static final class CopyFormatException extends Exception {

        private final String code;
        private final String recommendation;

        private CopyFormatException(String code, String recommendation) {
            super(code);
            this.code = code;
            this.recommendation = recommendation;
        }

        private String code() {
            return code;
        }

        private String recommendation() {
            return recommendation;
        }
    }

    private static final class UnsafeDataStatementException extends IllegalArgumentException {

        private UnsafeDataStatementException() {
            super("nonliteral or extended AST field");
        }

        private UnsafeDataStatementException(String message) {
            super(message);
        }
    }

    private record OracleQuote(char terminator) {
    }

    private static String withoutLineEnding(String line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\n') {
            end--;
        }
        if (end > 0 && line.charAt(end - 1) == '\r') {
            end--;
        }
        return line.substring(0, end);
    }

    private static String firstWord(String value) {
        String trimmed = stripBom(value).trim();
        int end = 0;
        while (end < trimmed.length()) {
            char current = trimmed.charAt(end);
            if (!Character.isLetterOrDigit(current) && current != '_' && current != '\\') {
                break;
            }
            end++;
        }
        return trimmed.substring(0, end);
    }

    private static String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    private static final class CountingInputStream extends FilterInputStream {

        private final long maxBytes;
        private long count;

        private CountingInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                addCount(1L);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, length);
            if (read > 0) {
                addCount(read);
            }
            return read;
        }

        private long count() {
            return count;
        }

        private void addCount(long amount) throws IOException {
            count += amount;
            if (count > maxBytes) {
                throw new IOException("SQL export exceeds " + maxBytes + " bytes while being read");
            }
        }
    }
}

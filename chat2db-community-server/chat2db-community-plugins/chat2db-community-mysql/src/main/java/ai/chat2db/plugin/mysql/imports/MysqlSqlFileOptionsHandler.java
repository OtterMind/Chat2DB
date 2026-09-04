package ai.chat2db.plugin.mysql.imports;

import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Executes SQL import statements with explicit error and commit policies. */
public final class MysqlSqlFileOptionsHandler implements ISqlBatchHandler {

    public static final String MODE_SCRIPT = "SCRIPT";
    public static final String MODE_BATCH = "BATCH";
    public static final String MODE_SINGLE_TRANSACTION = "SINGLE_TRANSACTION";
    public static final String POLICY_STOP = "STOP";
    public static final String POLICY_CONTINUE = "CONTINUE";
    private static final int DEFAULT_BATCH_SIZE = 1000;

    private final TaskExecutionContext context;
    private final Connection connection;
    private final String commitMode;
    private final String errorPolicy;
    private final int batchSize;
    private final int totalStatements;
    private final MysqlDmlEnginePreflight enginePreflight;
    private int statementNumber;
    private int successfulStatements;
    private int committedStatements;
    private int failedStatements;
    private int rolledBackStatements;
    private boolean transactionStarted;
    private boolean cancelled;
    private boolean terminalSummaryLogged;
    private Boolean originalAutoCommit;
    private final List<Integer> failedStatementNumbers = new ArrayList<>();
    private final List<Integer> uncommittedStatementNumbers = new ArrayList<>();
    private final List<String> rolledBackStatementRanges = new ArrayList<>();

    public static boolean supportsOptions(ImportTaskSpec spec) {
        return !MODE_SCRIPT.equals(normalize(StringUtils.defaultIfBlank(spec.getCommitMode(), MODE_SCRIPT)))
                || POLICY_CONTINUE.equals(normalize(StringUtils.defaultIfBlank(spec.getErrorPolicy(), POLICY_STOP)));
    }

    public MysqlSqlFileOptionsHandler(ImportTaskSpec spec, TaskExecutionContext context) {
        this(spec, context, Chat2DBContext.getConnection(), 0, false);
    }

    MysqlSqlFileOptionsHandler(ImportTaskSpec spec, TaskExecutionContext context, Connection connection) {
        this(spec, context, connection, 0, false);
    }

    public MysqlSqlFileOptionsHandler(ImportTaskSpec spec, TaskExecutionContext context, int totalStatements) {
        this(spec, context, Chat2DBContext.getConnection(), totalStatements, false);
    }

    public MysqlSqlFileOptionsHandler(ImportTaskSpec spec, TaskExecutionContext context, int totalStatements,
                                 boolean preflightDmlEngines) {
        this(spec, context, Chat2DBContext.getConnection(), totalStatements, preflightDmlEngines);
    }

    MysqlSqlFileOptionsHandler(ImportTaskSpec spec, TaskExecutionContext context, Connection connection,
                          int totalStatements) {
        this(spec, context, connection, totalStatements, false);
    }

    MysqlSqlFileOptionsHandler(ImportTaskSpec spec, TaskExecutionContext context, Connection connection,
                          int totalStatements, boolean preflightDmlEngines) {
        this.context = context;
        this.connection = connection;
        this.commitMode = normalize(StringUtils.defaultIfBlank(spec.getCommitMode(), MODE_SCRIPT));
        String requestedErrorPolicy = normalize(StringUtils.defaultIfBlank(spec.getErrorPolicy(), POLICY_STOP));
        this.errorPolicy = MODE_SINGLE_TRANSACTION.equals(commitMode) ? POLICY_STOP : requestedErrorPolicy;
        this.batchSize = spec.getBatchSize() == null || spec.getBatchSize() < 1
                ? DEFAULT_BATCH_SIZE : spec.getBatchSize();
        this.totalStatements = Math.max(totalStatements, 0);
        this.enginePreflight = preflightDmlEngines && !MODE_SCRIPT.equals(commitMode)
                ? new MysqlDmlEnginePreflight(spec, connection) : null;
        if (!MODE_SCRIPT.equals(commitMode) && !MODE_BATCH.equals(commitMode)
                && !MODE_SINGLE_TRANSACTION.equals(commitMode)) {
            throw fail("Unsupported SQL import commit mode: " + commitMode, null);
        }
        if (!POLICY_STOP.equals(requestedErrorPolicy) && !POLICY_CONTINUE.equals(requestedErrorPolicy)) {
            throw fail("Unsupported SQL import error policy: " + requestedErrorPolicy, null);
        }
    }

    public static ISqlBatchHandler preflightHandler(ImportTaskSpec spec, TaskExecutionContext context) {
        return new PreflightSqlBatchHandler(spec, context, null);
    }

    public static ISqlBatchHandler mysqlPreflightHandler(ImportTaskSpec spec, TaskExecutionContext context,
                                                         Connection connection) {
        return new PreflightSqlBatchHandler(spec, context, new MysqlDmlEnginePreflight(spec, connection));
    }

    @Override
    public void handle(Statement statement) {
        boolean failed = false;
        try {
            handleStatement(statement);
        } catch (RuntimeException e) {
            failed = true;
            cancelled = e instanceof TaskCancelledException;
            rollbackQuietly(e);
            logTerminalSummaryQuietly(cancelled ? "SQL file execution cancelled" : "SQL file execution stopped", e);
            throw e;
        } finally {
            if (failed) {
                restoreAutoCommit();
            }
        }
    }

    private void handleStatement(Statement statement) {
        context.checkCancelled();
        String sql = statement.getSql() == null ? "" : statement.getSql().trim();
        if (StringUtils.isBlank(sql) || ";".equals(sql)) {
            return;
        }
        statementNumber++;
        if (!MODE_SCRIPT.equals(commitMode)) {
            validateTransactionSafe(sql);
            if (enginePreflight != null) {
                enginePreflight.validate(sql, statementNumber);
            }
            beginTransaction();
        }
        java.sql.Statement jdbcStatement = null;
        try {
            jdbcStatement = connection.createStatement();
            context.onStatementCreated(jdbcStatement);
            jdbcStatement.execute(sql);
            successfulStatements++;
            if (MODE_SCRIPT.equals(commitMode)) {
                committedStatements++;
            } else {
                uncommittedStatementNumbers.add(statementNumber);
                if (MODE_BATCH.equals(commitMode) && uncommittedStatementNumbers.size() >= batchSize) {
                    commit();
                }
            }
        } catch (SQLException e) {
            throwIfCancelled(e);
            failedStatements++;
            failedStatementNumbers.add(statementNumber);
            logFailure(statement, e);
            if (POLICY_STOP.equals(errorPolicy) || MODE_SINGLE_TRANSACTION.equals(commitMode)) {
                rollback();
                throw fail("SQL import failed at statement " + statementNumber, e);
            }
        } finally {
            if (jdbcStatement != null) {
                try {
                    jdbcStatement.close();
                } catch (SQLException ignored) {
                    // The connection lifecycle owns cleanup after a statement close failure.
                }
                context.onStatementClosed(jdbcStatement);
            }
        }
    }

    @Override
    public void flush() {
        try {
            context.checkCancelled();
            if (transactionStarted) {
                commit();
            }
            logTerminalSummary("SQL file execution completed");
        } catch (RuntimeException e) {
            cancelled = e instanceof TaskCancelledException;
            rollbackQuietly(e);
            logTerminalSummaryQuietly(cancelled ? "SQL file execution cancelled" : "SQL file execution stopped", e);
            throw e;
        } finally {
            restoreAutoCommit();
        }
    }

    private void beginTransaction() {
        if (transactionStarted) {
            return;
        }
        try {
            if (originalAutoCommit == null) {
                originalAutoCommit = connection.getAutoCommit();
            }
            connection.setAutoCommit(false);
            transactionStarted = true;
        } catch (SQLException e) {
            throw fail("Could not start SQL import transaction", e);
        }
    }

    private void commit() {
        try {
            connection.commit();
            committedStatements += uncommittedStatementNumbers.size();
            uncommittedStatementNumbers.clear();
            transactionStarted = false;
        } catch (SQLException e) {
            throw fail("Could not commit SQL import transaction", e);
        }
    }

    private void rollback() {
        if (!transactionStarted) {
            return;
        }
        try {
            connection.rollback();
            rolledBackStatements += uncommittedStatementNumbers.size();
            rolledBackStatementRanges.addAll(compactRanges(uncommittedStatementNumbers));
            uncommittedStatementNumbers.clear();
            transactionStarted = false;
        } catch (SQLException ignored) {
            // The original failure is more useful to the task result than a rollback failure.
        }
    }

    private void rollbackQuietly(RuntimeException failure) {
        try {
            rollback();
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void restoreAutoCommit() {
        if (originalAutoCommit == null) {
            return;
        }
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException ignored) {
            // The connection is task-scoped and will be closed by task cleanup.
        }
    }

    private void throwIfCancelled(SQLException cause) {
        try {
            context.checkCancelled();
        } catch (TaskCancelledException cancelledException) {
            cancelledException.addSuppressed(cause);
            throw cancelledException;
        }
        if (isCancellationSQLException(cause)) {
            TaskCancelledException cancelledException = new TaskCancelledException();
            cancelledException.addSuppressed(cause);
            throw cancelledException;
        }
    }

    private static boolean isCancellationSQLException(SQLException cause) {
        String sqlState = cause.getSQLState();
        if ("57014".equals(sqlState) || "70100".equals(sqlState)) {
            return true;
        }
        String message = StringUtils.defaultString(cause.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("cancelled") || message.contains("canceled")
                || message.contains("query execution was interrupted");
    }

    private void validateTransactionSafe(String sql) {
        validateTransactionSafe(sql, statementNumber);
    }

    private static void validateTransactionSafe(String sql, int statementNumber) {
        String upper = stripLeadingComments(sql).toUpperCase(Locale.ROOT);
        if (upper.startsWith("START TRANSACTION") || upper.startsWith("BEGIN") || upper.startsWith("COMMIT")
                || upper.startsWith("ROLLBACK") || upper.startsWith("SET AUTOCOMMIT")) {
            throw fail("Transaction-control SQL at statement " + statementNumber
                    + " requires SCRIPT commit mode", null);
        }
        if (upper.startsWith("CREATE") || upper.startsWith("ALTER") || upper.startsWith("DROP")
                || upper.startsWith("TRUNCATE") || upper.startsWith("RENAME") || upper.startsWith("GRANT")
                || upper.startsWith("REVOKE") || upper.startsWith("FLUSH") || upper.startsWith("LOCK")
                || upper.startsWith("UNLOCK") || upper.startsWith("OPTIMIZE") || upper.startsWith("ANALYZE")
                || upper.startsWith("CHECK ") || upper.startsWith("REPAIR") || upper.startsWith("LOAD ")
                || upper.startsWith("SET ")) {
            throw fail("DDL, implicit-commit, or non-transactional SQL at statement " + statementNumber
                    + " requires SCRIPT commit mode", null);
        }
    }

    private static String stripLeadingComments(String sql) {
        int index = 0;
        while (index < sql.length()) {
            while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
                index++;
            }
            if (index >= sql.length()) {
                break;
            }
            if (sql.startsWith("--", index) || sql.charAt(index) == '#') {
                int lineEnd = sql.indexOf('\n', index);
                if (lineEnd < 0) {
                    return "";
                }
                index = lineEnd + 1;
                continue;
            }
            if (sql.startsWith("/*", index)) {
                if (sql.startsWith("/*!", index)) {
                    int commentEnd = sql.indexOf("*/", index + 3);
                    if (commentEnd < 0) {
                        return stripExecutableCommentVersion(sql.substring(index + 3));
                    }
                    return (stripExecutableCommentVersion(sql.substring(index + 3, commentEnd))
                            + " " + sql.substring(commentEnd + 2)).trim();
                }
                int commentEnd = sql.indexOf("*/", index + 2);
                if (commentEnd < 0) {
                    return "";
                }
                index = commentEnd + 2;
                continue;
            }
            break;
        }
        return sql.substring(index).trim();
    }

    private static String stripExecutableCommentVersion(String sql) {
        String trimmed = sql.trim();
        int index = 0;
        while (index < trimmed.length() && Character.isDigit(trimmed.charAt(index))) {
            index++;
        }
        return index >= 5 ? trimmed.substring(index).trim() : trimmed;
    }

    private void logFailure(Statement statement, SQLException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("statement", statementNumber);
        if (statement.getFirstToken() != null) {
            details.put("startLine", statement.getFirstToken().getLine());
        }
        if (statement.getLastToken() != null) {
            details.put("endLine", statement.getLastToken().getLine());
        }
        details.put("errorCode", e.getErrorCode());
        details.put("sqlState", e.getSQLState());
        details.put("message", e.getMessage());
        context.logError(TaskEventCode.OBJECT_SKIPPED.name(), "SQL statement failed", details);
    }

    private Map<String, Object> summary() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("totalStatements", knownTotalStatements());
        details.put("successfulStatements", successfulStatements);
        details.put("failedStatements", failedStatements);
        details.put("failedStatementNumbers", List.copyOf(failedStatementNumbers));
        details.put("committedStatements", committedStatements);
        details.put("rolledBackStatements", rolledBackStatements);
        details.put("rolledBackStatementRanges", List.copyOf(rolledBackStatementRanges));
        details.put("unexecutedStatements", unexecutedStatements());
        putIfNotNull(details, "unexecutedStatementRange", unexecutedStatementRange());
        details.put("cancelled", cancelled);
        putIfNotNull(details, "cancelledStatement", cancelledStatement());
        details.put("commitMode", commitMode);
        details.put("errorPolicy", errorPolicy);
        details.put("batchSize", batchSize);
        return details;
    }

    private void putIfNotNull(Map<String, Object> details, String key, Object value) {
        if (value != null) {
            details.put(key, value);
        }
    }

    private void logTerminalSummary(String message) {
        if (terminalSummaryLogged) {
            return;
        }
        terminalSummaryLogged = true;
        context.logInfo(TaskEventCode.BATCH_EXECUTED.name(), message, summary());
    }

    private void logTerminalSummaryQuietly(String message, RuntimeException failure) {
        try {
            logTerminalSummary(message);
        } catch (TaskCancelledException summaryCancellation) {
            cancelled = true;
            failure.addSuppressed(summaryCancellation);
        } catch (RuntimeException summaryFailure) {
            failure.addSuppressed(summaryFailure);
        }
    }

    private int knownTotalStatements() {
        return Math.max(totalStatements, statementNumber);
    }

    private int unexecutedStatements() {
        return Math.max(knownTotalStatements() - statementNumber, 0);
    }

    private String unexecutedStatementRange() {
        int unexecutedStatements = unexecutedStatements();
        if (unexecutedStatements == 0) {
            return "";
        }
        return range(statementNumber + 1, knownTotalStatements());
    }

    private Integer cancelledStatement() {
        if (!cancelled) {
            return null;
        }
        int nextStatement = statementNumber + 1;
        return totalStatements == 0 || nextStatement <= totalStatements ? nextStatement : null;
    }

    private static List<String> compactRanges(List<Integer> statementNumbers) {
        if (statementNumbers.isEmpty()) {
            return List.of();
        }
        List<Integer> sorted = new ArrayList<>(statementNumbers);
        Collections.sort(sorted);
        List<String> ranges = new ArrayList<>();
        int start = sorted.get(0);
        int previous = start;
        for (int i = 1; i < sorted.size(); i++) {
            int current = sorted.get(i);
            if (current == previous + 1) {
                previous = current;
                continue;
            }
            ranges.add(range(start, previous));
            start = current;
            previous = current;
        }
        ranges.add(range(start, previous));
        return ranges;
    }

    private static String range(int start, int end) {
        return start == end ? String.valueOf(start) : start + "-" + end;
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static TaskExecutionException fail(String message, Throwable cause) {
        return new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(), message, cause);
    }

    private static final class MysqlDmlEnginePreflight {
        private static final String ENGINE_SQL = """
                SELECT ENGINE
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                """;
        private static final Set<String> TRANSACTIONAL_ENGINES = Set.of("INNODB", "NDB", "NDBCLUSTER");
        private final Connection connection;
        private final String defaultDatabaseName;
        private final Map<String, String> engineCache = new HashMap<>();

        private MysqlDmlEnginePreflight(ImportTaskSpec spec, Connection connection) {
            this.connection = connection;
            this.defaultDatabaseName = resolveDefaultDatabaseName(spec, connection);
        }

        private void validate(String sql, int statementNumber) {
            MysqlTableName target = dmlTarget(sql, statementNumber);
            if (target == null) {
                return;
            }
            String databaseName = StringUtils.defaultIfBlank(target.databaseName(), defaultDatabaseName);
            if (StringUtils.isBlank(databaseName)) {
                throw fail("Could not resolve MySQL DML target database at statement " + statementNumber, null);
            }
            String engine = engine(databaseName, target.tableName(), statementNumber);
            if (!TRANSACTIONAL_ENGINES.contains(engine.toUpperCase(Locale.ROOT))) {
                throw fail("MySQL DML target " + databaseName + "." + target.tableName()
                        + " uses non-transactional or unsupported engine " + engine
                        + " at statement " + statementNumber, null);
            }
        }

        private String engine(String databaseName, String tableName, int statementNumber) {
            String key = databaseName + "." + tableName;
            if (engineCache.containsKey(key)) {
                return engineCache.get(key);
            }
            try (PreparedStatement statement = connection.prepareStatement(ENGINE_SQL)) {
                statement.setString(1, databaseName);
                statement.setString(2, tableName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw fail("Could not resolve MySQL DML target " + databaseName + "." + tableName
                                + " at statement " + statementNumber, null);
                    }
                    String engine = resultSet.getString("ENGINE");
                    if (StringUtils.isBlank(engine)) {
                        throw fail("Could not resolve MySQL DML target engine for " + databaseName + "." + tableName
                                + " at statement " + statementNumber, null);
                    }
                    engineCache.put(key, engine);
                    return engine;
                }
            } catch (TaskExecutionException e) {
                throw e;
            } catch (SQLException e) {
                throw fail("Could not verify MySQL DML target engine for " + databaseName + "." + tableName
                        + " at statement " + statementNumber, e);
            }
        }

        private static String resolveDefaultDatabaseName(ImportTaskSpec spec, Connection connection) {
            if (spec != null && spec.getTarget() != null
                    && StringUtils.isNotBlank(spec.getTarget().getDatabaseName())) {
                return spec.getTarget().getDatabaseName().trim();
            }
            ConnectInfoSnapshot snapshot = connectInfoSnapshot();
            if (StringUtils.isNotBlank(snapshot.databaseName())) {
                return snapshot.databaseName();
            }
            try {
                return StringUtils.trimToNull(connection.getCatalog());
            } catch (SQLException ignored) {
                return null;
            }
        }

        private static ConnectInfoSnapshot connectInfoSnapshot() {
            try {
                ai.chat2db.spi.model.datasource.ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
                return connectInfo == null ? new ConnectInfoSnapshot(null)
                        : new ConnectInfoSnapshot(StringUtils.trimToNull(connectInfo.getDatabaseName()));
            } catch (RuntimeException ignored) {
                return new ConnectInfoSnapshot(null);
            }
        }

        private static MysqlTableName dmlTarget(String sql, int statementNumber) {
            List<String> tokens = mysqlIdentifierTokens(stripLeadingComments(sql));
            if (tokens.isEmpty()) {
                return null;
            }
            String first = tokens.get(0).toUpperCase(Locale.ROOT);
            return switch (first) {
                case "INSERT" -> targetAfter(tokens, statementNumber, "INTO");
                case "REPLACE" -> targetAfterOptionalInto(tokens, statementNumber);
                case "UPDATE" -> targetAfterUpdate(tokens, statementNumber);
                case "DELETE" -> targetAfterDelete(tokens, statementNumber);
                default -> null;
            };
        }

        private static MysqlTableName targetAfter(List<String> tokens, int statementNumber, String requiredKeyword) {
            int index = indexOfKeyword(tokens, requiredKeyword, 1);
            if (index < 0 || index + 1 >= tokens.size()) {
                throw fail("Could not resolve MySQL DML target at statement " + statementNumber, null);
            }
            return parseQualifiedName(tokens.get(index + 1), statementNumber);
        }

        private static MysqlTableName targetAfterOptionalInto(List<String> tokens, int statementNumber) {
            int index = 1;
            while (index < tokens.size() && StringUtils.equalsAnyIgnoreCase(tokens.get(index),
                    "LOW_PRIORITY", "DELAYED")) {
                index++;
            }
            if (index < tokens.size() && "INTO".equalsIgnoreCase(tokens.get(index))) {
                index++;
            }
            if (index >= tokens.size()) {
                throw fail("Could not resolve MySQL DML target at statement " + statementNumber, null);
            }
            return parseQualifiedName(tokens.get(index), statementNumber);
        }

        private static MysqlTableName targetAfterUpdate(List<String> tokens, int statementNumber) {
            int index = 1;
            while (index < tokens.size() && StringUtils.equalsAnyIgnoreCase(tokens.get(index),
                    "LOW_PRIORITY", "IGNORE")) {
                index++;
            }
            if (index >= tokens.size() || indexOfKeyword(tokens, ",", index) >= 0) {
                int setIndex = indexOfKeyword(tokens, "SET", index + 1);
                int commaIndex = indexOfKeyword(tokens, ",", index + 1);
                if (index >= tokens.size() || (commaIndex >= 0 && (setIndex < 0 || commaIndex < setIndex))) {
                    throw fail("Could not resolve MySQL UPDATE target at statement " + statementNumber, null);
                }
            }
            if (index >= tokens.size()) {
                throw fail("Could not resolve MySQL UPDATE target at statement " + statementNumber, null);
            }
            return parseQualifiedName(tokens.get(index), statementNumber);
        }

        private static MysqlTableName targetAfterDelete(List<String> tokens, int statementNumber) {
            int index = 1;
            while (index < tokens.size() && StringUtils.equalsAnyIgnoreCase(tokens.get(index),
                    "LOW_PRIORITY", "QUICK", "IGNORE")) {
                index++;
            }
            if (index < tokens.size() && "FROM".equalsIgnoreCase(tokens.get(index)) && index + 1 < tokens.size()) {
                return parseQualifiedName(tokens.get(index + 1), statementNumber);
            }
            throw fail("Could not resolve MySQL DELETE target at statement " + statementNumber, null);
        }

        private static int indexOfKeyword(List<String> tokens, String keyword, int start) {
            for (int i = start; i < tokens.size(); i++) {
                if (keyword.equalsIgnoreCase(tokens.get(i))) {
                    return i;
                }
            }
            return -1;
        }

        private static MysqlTableName parseQualifiedName(String token, int statementNumber) {
            List<String> parts = splitQualifiedName(token);
            if (parts.size() == 1 && StringUtils.isNotBlank(parts.get(0))) {
                return new MysqlTableName(null, parts.get(0));
            }
            if (parts.size() == 2 && StringUtils.isNoneBlank(parts.get(0), parts.get(1))) {
                return new MysqlTableName(parts.get(0), parts.get(1));
            }
            throw fail("Could not resolve MySQL DML target at statement " + statementNumber, null);
        }

        private static List<String> splitQualifiedName(String token) {
            List<String> parts = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c == '`') {
                    if (quoted && i + 1 < token.length() && token.charAt(i + 1) == '`') {
                        current.append('`');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (c == '.' && !quoted) {
                    parts.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            parts.add(current.toString());
            return parts;
        }

        private static List<String> mysqlIdentifierTokens(String sql) {
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean quotedIdentifier = false;
            boolean quotedString = false;
            char stringQuote = 0;
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (quotedString) {
                    if (c == stringQuote && (i == 0 || sql.charAt(i - 1) != '\\')) {
                        quotedString = false;
                    }
                    continue;
                }
                if (quotedIdentifier) {
                    current.append(c);
                    if (c == '`') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '`') {
                            current.append(sql.charAt(++i));
                        } else {
                            quotedIdentifier = false;
                        }
                    }
                    continue;
                }
                if (c == '\'' || c == '"') {
                    flushToken(tokens, current);
                    quotedString = true;
                    stringQuote = c;
                } else if (c == '`') {
                    if (current.length() == 0 || current.charAt(current.length() - 1) != '.') {
                        flushToken(tokens, current);
                    }
                    quotedIdentifier = true;
                    current.append(c);
                } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') {
                    current.append(c);
                } else {
                    flushToken(tokens, current);
                    if (c == ',') {
                        tokens.add(",");
                    }
                }
            }
            flushToken(tokens, current);
            return tokens;
        }

        private static void flushToken(List<String> tokens, StringBuilder current) {
            if (current.length() == 0) {
                return;
            }
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private record MysqlTableName(String databaseName, String tableName) {
    }

    private record ConnectInfoSnapshot(String databaseName) {
    }

    private static final class PreflightSqlBatchHandler implements ISqlBatchHandler {
        private final String commitMode;
        private final TaskExecutionContext context;
        private final MysqlDmlEnginePreflight enginePreflight;
        private int statementNumber;

        private PreflightSqlBatchHandler(ImportTaskSpec spec, TaskExecutionContext context,
                                         MysqlDmlEnginePreflight enginePreflight) {
            this.commitMode = normalize(StringUtils.defaultIfBlank(spec.getCommitMode(), MODE_SCRIPT));
            this.context = context;
            this.enginePreflight = enginePreflight;
        }

        @Override
        public void handle(Statement statement) {
            context.checkCancelled();
            String sql = statement.getSql() == null ? "" : statement.getSql().trim();
            if (StringUtils.isBlank(sql) || ";".equals(sql)) {
                return;
            }
            statementNumber++;
            if (!MODE_SCRIPT.equals(commitMode)) {
                validateTransactionSafe(sql, statementNumber);
                if (enginePreflight != null) {
                    enginePreflight.validate(sql, statementNumber);
                }
            }
        }

        @Override
        public void flush() {
            context.checkCancelled();
        }
    }
}

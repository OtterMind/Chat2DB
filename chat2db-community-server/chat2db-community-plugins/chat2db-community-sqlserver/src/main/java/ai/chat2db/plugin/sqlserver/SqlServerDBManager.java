package ai.chat2db.plugin.sqlserver;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.plugin.sqlserver.identifier.SqlServerIdentifierProcessor;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.sqlserver.constant.SQLConstant.*;
import static ai.chat2db.plugin.sqlserver.constant.SqlServerDBManagerConstants.*;
import static cn.hutool.core.date.DatePattern.NORM_DATETIME_PATTERN;

@Slf4j
public class SqlServerDBManager extends DefaultDBManager implements IDbManager {

    private static final Pattern GO_BATCH_LINE = Pattern.compile("(?i)^\\s*go\\s*;?\\s*(?:--.*)?$");
    private static final Pattern GO_EXTENDED_PROPERTY_LINE = Pattern.compile(
            "(?i)^\\s*go\\s*;?\\s+(exec\\s+sp_addextendedproperty\\b.*)$");
    private static final Pattern CREATE_INDEX_BATCH = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:UNIQUE\\s+)?(?:CLUSTERED\\s+|NONCLUSTERED\\s+|SPATIAL\\s+|XML\\s+)?INDEX\\b");
    private static final Pattern NAMED_TABLE_CONSTRAINT = Pattern.compile(
            "(?im)^(\\s*)constraint\\s+[^\\r\\n]+\\R(?=\\s*(?:primary\\s+key|unique|check|foreign\\s+key)\\b)");
    private static final Pattern CONSTRAINT_COMMENT = Pattern.compile(
            "(?i)'CONSTRAINT'\\s*,");








    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) {
        asyncContext.write(String.format(EXPORT_TITLE, DateUtil.format(new Date(), NORM_DATETIME_PATTERN)));
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting tables");
        exportTables(connection, databaseName, schemaName, asyncContext);
        asyncContext.setProgress(50);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting views");
        exportViews(connection, databaseName, schemaName, asyncContext);
        asyncContext.setProgress(60);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting producers");
        exportProcedures(connection, schemaName, asyncContext);
        asyncContext.setProgress(70);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting triggers");
        exportTriggers(connection, schemaName, asyncContext);
        asyncContext.setProgress(80);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting functions");
        exportFunctions(connection, schemaName, asyncContext);
        asyncContext.setProgress(90);
    }

    private void exportTables(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().preExecute(connection, ORDER_TABLES_SQL, new String[]{schemaName, schemaName, schemaName}, resultSet -> {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                exportTable(connection, databaseName, schemaName, tableName, asyncContext);
            }
        });
    }


    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) {
        String tableDDL = Chat2DBContext.getDbMetaData().tableDDL(connection,
                new TableMetadataRequest(databaseName, schemaName, tableName));
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(SQL_DROP_TABLE_EXISTS)
                .append(buildFullTableName(null, schemaName, tableName)).append(";").append("\ngo\n")
                .append(tableDDL);
        asyncContext.write(sqlBuilder.toString());
        if (asyncContext.isContainsData()) {
            exportTableData(connection, databaseName, schemaName, tableName, asyncContext);
        } else {
            asyncContext.write("go \n");
        }
    }
    @Override
    public void exportTableData(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) {
        exportTableData(connection, databaseName, schemaName, tableName, asyncContext, 10000);
        asyncContext.write("go \n");
    }


    private void exportViews(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().preExecute(connection, VIEWS_DDL_SQL, new String[]{schemaName, databaseName}, resultSet -> {
            while (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQL_DROP_VIEW_EXISTS)
                        .append(buildFullTableName(null, schemaName, resultSet.getString("TABLE_NAME")))
                        .append(";\n").append("go").append("\n")
                        .append(resultSet.getString("VIEW_DEFINITION")).append(";").append("\n")
                        .append("go").append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        });
    }


    private void exportFunctions(Connection connection, String schemaName, AsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().preExecute(connection, ROUTINES_SQL, new String[]{"FN", schemaName}, resultSet -> {
            while (resultSet.next()) {
                String functionName = resultSet.getString("name");
                exportFunction(connection, schemaName, functionName, asyncContext);
            }
        });

    }

    private void exportFunction(Connection connection, String schemaName, String functionName,
                                AsyncContext asyncContext) {
        String sql = String.format(ROUTINES_DDL_SQL,
                "'SQL_SCALAR_FUNCTION', 'SQL_TABLE_VALUED_FUNCTION'",
                SqlServerIdentifierProcessor.INSTANCE.escapeString(functionName),
                SqlServerIdentifierProcessor.INSTANCE.escapeString(schemaName));
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                String qualifiedName = buildFullTableName(null, schemaName, functionName);
                sqlBuilder.append(String.format(DROP_FUNCTION_SQL,
                        SqlServerIdentifierProcessor.INSTANCE.escapeString(qualifiedName), qualifiedName));
                sqlBuilder.append(resultSet.getString("definition"))
                        .append("\n").append("go").append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        });
    }


    private void exportProcedures(Connection connection, String schemaName, AsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().preExecute(connection, ROUTINES_SQL, new String[]{"P", schemaName}, resultSet -> {
            while (resultSet.next()) {
                String procedureName = resultSet.getString("name");
                exportProcedure(connection, schemaName, procedureName, asyncContext);
            }
        });
    }

    private void exportProcedure(Connection connection, String schemaName, String procedureName,
                                 AsyncContext asyncContext) throws SQLException {
        String sql = String.format(ROUTINES_DDL_SQL, "'SQL_STORED_PROCEDURE'",
                SqlServerIdentifierProcessor.INSTANCE.escapeString(procedureName),
                SqlServerIdentifierProcessor.INSTANCE.escapeString(schemaName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                String qualifiedName = buildFullTableName(null, schemaName, procedureName);
                sqlBuilder.append(String.format(DROP_PROCEDURE_SQL,
                        SqlServerIdentifierProcessor.INSTANCE.escapeString(qualifiedName), qualifiedName));
                sqlBuilder.append(resultSet.getString("definition")).append("\n").append("go").append("\n");
                asyncContext.write(sqlBuilder.toString());

            }
        }
    }

    private void exportTriggers(Connection connection, String schemaName, AsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().preExecute(connection, TRIGGERS_SQL, new String[]{schemaName}, resultSet -> {
            while (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("triggerDefinition")).append("\n").append("go").append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        });
    }

    @Override
    public void connectDatabase(Connection connection, String database) {
        try {
            DefaultSQLExecutor.getInstance().execute(connection,
                    String.format(SQL_USE_DATABASE,
                            SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(database)));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName, boolean copyData) throws SQLException {
        tableName = unquoteIdentifier(tableName);
        newTableName = unquoteIdentifier(newTableName);
        String ddl = Chat2DBContext.getDbMetaData().tableDDL(connection,
                new TableMetadataRequest(databaseName, schemaName, tableName));
        List<String> batches = prepareCopyDdlBatches(ddl, databaseName, schemaName, tableName, newTableName);
        for (String batch : batches) {
            log.info("copy table DDL batch: {}", batch);
            DefaultSQLExecutor.getInstance().execute(connection, batch, resultSet -> null);
        }

        if (copyData) {
            List<String> copyableColumns = new ArrayList<>();
            boolean hasIdentity = false;
            try (PreparedStatement ps = connection.prepareStatement(SELECT_TABLE_COLUMNS)) {
                ps.setString(1, schemaName);
                ps.setString(2, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String computedDef = rs.getString("COMPUTED_DEFINITION");
                        String dataType = rs.getString("DATA_TYPE");
                        if (!isCopyableColumn(computedDef, dataType)) {
                            continue;
                        }
                        if (rs.getBoolean("IS_IDENTITY")) {
                            hasIdentity = true;
                        }
                        copyableColumns.add(quoteIdentifier(rs.getString("COLUMN_NAME")));
                    }
                }
            }

            if (copyableColumns.isEmpty()) {
                log.warn("No copyable columns found for table {}", tableName);
                return;
            }

            String columnList = String.join(", ", copyableColumns);
            String sourceTable = buildFullTableName(databaseName, schemaName, tableName);
            String targetTable = buildFullTableName(databaseName, schemaName, newTableName);
            String insertSql = buildCopyDataSql(targetTable, columnList, sourceTable);
            log.info("copy table data sql: {}", insertSql);

            if (hasIdentity) {
                executeIdentityCopy(targetTable, insertSql,
                        sql -> DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null));
            } else {
                DefaultSQLExecutor.getInstance().execute(connection, insertSql, resultSet -> null);
            }
        }
    }

    static List<String> prepareCopyDdlBatches(String ddl, String databaseName, String schemaName,
            String tableName, String newTableName) {
        if (StringUtils.isBlank(ddl)) {
            throw new IllegalArgumentException("Source table DDL is empty");
        }

        List<String> sourceReferences = tableReferences(databaseName, schemaName, tableName);
        String targetTable = buildFullTableName(null, schemaName, newTableName);
        List<String> rewrittenBatches = new ArrayList<>();
        boolean createTableRewritten = false;

        for (String batch : splitDdlBatches(ddl)) {
            String rewritten = batch;
            if (startsWithKeyword(batch, "CREATE\\s+TABLE")) {
                rewritten = replaceObjectAfterKeyword(batch, "CREATE\\s+TABLE\\s+", sourceReferences,
                        targetTable);
                rewritten = rewriteSelfReference(rewritten, sourceReferences, tableName, targetTable);
                // Constraint names are schema-scoped, so copied declarations must receive fresh server-generated names.
                rewritten = removeNamedTableConstraints(rewritten);
                createTableRewritten = true;
            } else if (CREATE_INDEX_BATCH.matcher(batch).find()) {
                rewritten = replaceObjectAfterKeyword(batch, "ON\\s+", sourceReferences, targetTable);
            } else if (isExtendedPropertyBatch(batch)) {
                if (CONSTRAINT_COMMENT.matcher(batch).find()) {
                    // Auto-generated target constraint names cannot be addressed by the source constraint comment.
                    continue;
                }
                rewritten = rewriteExtendedPropertyTable(batch, tableName, newTableName);
            }
            rewrittenBatches.add(rewritten.trim());
        }

        if (!createTableRewritten) {
            throw new IllegalArgumentException("Unable to locate the source CREATE TABLE statement");
        }
        return rewrittenBatches;
    }

    static List<String> splitDdlBatches(String ddl) {
        List<String> batches = new ArrayList<>();
        StringBuilder batch = new StringBuilder();
        DdlLexicalState state = new DdlLexicalState();
        String[] lines = ddl.split("\\R", -1);
        for (String line : lines) {
            if (state.isCode()) {
                if (GO_BATCH_LINE.matcher(line).matches()) {
                    addBatch(batches, batch);
                    continue;
                }
                Matcher inlineBatch = GO_EXTENDED_PROPERTY_LINE.matcher(line);
                if (inlineBatch.matches()) {
                    addBatch(batches, batch);
                    String extendedProperty = inlineBatch.group(1);
                    batch.append(extendedProperty).append('\n');
                    updateLexicalState(extendedProperty, state);
                    continue;
                }
            }
            batch.append(line).append('\n');
            updateLexicalState(line, state);
        }
        addBatch(batches, batch);
        return batches;
    }

    private static void updateLexicalState(String line, DdlLexicalState state) {
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
            if (state.inString) {
                if (current == '\'' && next == '\'') {
                    i++;
                } else if (current == '\'') {
                    state.inString = false;
                }
                continue;
            }

            if (state.blockCommentDepth > 0) {
                if (current == '/' && next == '*') {
                    state.blockCommentDepth++;
                    i++;
                } else if (current == '*' && next == '/') {
                    state.blockCommentDepth--;
                    i++;
                }
                continue;
            }

            if (current == '-' && next == '-') {
                return;
            }
            if (current == '/' && next == '*') {
                state.blockCommentDepth++;
                i++;
            } else if (current == '\'') {
                state.inString = true;
            }
        }
    }

    private static void addBatch(List<String> batches, StringBuilder batch) {
        String sql = batch.toString().trim();
        if (StringUtils.isNotBlank(sql)) {
            batches.add(sql);
        }
        batch.setLength(0);
    }

    private static boolean startsWithKeyword(String sql, String keywordPattern) {
        return Pattern.compile("(?is)^\\s*" + keywordPattern + "\\b").matcher(sql).find();
    }

    private static boolean isExtendedPropertyBatch(String sql) {
        return Pattern.compile("(?is)^\\s*exec\\s+sp_addextendedproperty\\b").matcher(sql).find();
    }

    private static String replaceObjectAfterKeyword(String sql, String keywordPattern, List<String> sourceReferences,
            String targetReference) {
        String alternatives = sourceReferences.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        Pattern pattern = Pattern.compile("(?i)(\\b" + keywordPattern + ")(?:(?:" + alternatives + "))");
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            if (isSqlCodeAt(sql, matcher.start())) {
                return sql.substring(0, matcher.start()) + matcher.group(1) + targetReference
                        + sql.substring(matcher.end());
            }
        }
        throw new IllegalArgumentException("Unable to rewrite source table reference in DDL batch: " + sql);
    }

    private static String rewriteSelfReference(String sql, List<String> sourceReferences, String tableName,
            String targetReference) {
        Set<String> references = new LinkedHashSet<>(sourceReferences);
        references.add(quoteIdentifier(tableName));
        references.add(tableName);
        String alternatives = references.stream()
                .filter(StringUtils::isNotBlank)
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        Pattern pattern = Pattern.compile("(?i)(\\breferences\\s+)(?:(?:" + alternatives + "))(?=\\s*\\()");
        Matcher matcher = pattern.matcher(sql);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            if (isSqlCodeAt(sql, matcher.start())) {
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(1) + targetReference));
            }
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String removeNamedTableConstraints(String sql) {
        Matcher matcher = NAMED_TABLE_CONSTRAINT.matcher(sql);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            if (isSqlCodeAt(sql, matcher.start())) {
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(1)));
            }
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static boolean isSqlCodeAt(String sql, int position) {
        DdlLexicalState state = new DdlLexicalState();
        boolean inLineComment = false;
        boolean inBracketIdentifier = false;
        boolean inQuotedIdentifier = false;
        for (int i = 0; i < position; i++) {
            char current = sql.charAt(i);
            char next = i + 1 < position ? sql.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (state.inString) {
                if (current == '\'' && next == '\'') {
                    i++;
                } else if (current == '\'') {
                    state.inString = false;
                }
                continue;
            }
            if (inBracketIdentifier) {
                if (current == ']' && next == ']') {
                    i++;
                } else if (current == ']') {
                    inBracketIdentifier = false;
                }
                continue;
            }
            if (inQuotedIdentifier) {
                if (current == '"' && next == '"') {
                    i++;
                } else if (current == '"') {
                    inQuotedIdentifier = false;
                }
                continue;
            }
            if (state.blockCommentDepth > 0) {
                if (current == '/' && next == '*') {
                    state.blockCommentDepth++;
                    i++;
                } else if (current == '*' && next == '/') {
                    state.blockCommentDepth--;
                    i++;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                inLineComment = true;
                i++;
            } else if (current == '/' && next == '*') {
                state.blockCommentDepth++;
                i++;
            } else if (current == '\'') {
                state.inString = true;
            } else if (current == '[') {
                inBracketIdentifier = true;
            } else if (current == '"') {
                inQuotedIdentifier = true;
            }
        }
        return !inLineComment && state.isCode() && !inBracketIdentifier && !inQuotedIdentifier;
    }

    private static String rewriteExtendedPropertyTable(String sql, String tableName, String newTableName) {
        Pattern pattern = Pattern.compile("(?i)('TABLE'\\s*,\\s*N')" + Pattern.quote(tableName) + "(')");
        Matcher matcher = pattern.matcher(sql);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Unable to rewrite table extended property: " + sql);
        }
        String replacement = matcher.group(1) + newTableName.replace("'", "''") + matcher.group(2);
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    static boolean isCopyableColumn(String computedDefinition, String dataType) {
        return StringUtils.isBlank(computedDefinition)
                && !"timestamp".equalsIgnoreCase(dataType)
                && !"rowversion".equalsIgnoreCase(dataType);
    }

    static String buildCopyDataSql(String targetTable, String columnList, String sourceTable) {
        return String.format(SQL_COPY_TABLE_DATA_WITH_COLUMNS, targetTable, columnList, columnList, sourceTable);
    }

    static void executeIdentityCopy(String targetTable, String insertSql, Consumer<String> executor) {
        String identityOn = String.format(SQL_SET_IDENTITY_INSERT, targetTable, "ON");
        String identityOff = String.format(SQL_SET_IDENTITY_INSERT, targetTable, "OFF");
        boolean identityEnableAttempted = false;
        RuntimeException copyFailure = null;
        try {
            identityEnableAttempted = true;
            executor.accept(identityOn);
            executor.accept(insertSql);
        } catch (RuntimeException exception) {
            copyFailure = exception;
            log.error("Failed to copy data with identity insert", exception);
            throw exception;
        } finally {
            if (identityEnableAttempted) {
                try {
                    executor.accept(identityOff);
                } catch (RuntimeException exception) {
                    if (copyFailure != null) {
                        copyFailure.addSuppressed(exception);
                        log.warn("Failed to turn off identity insert", exception);
                    } else {
                        throw exception;
                    }
                }
            }
        }
    }

    private static List<String> tableReferences(String databaseName, String schemaName, String tableName) {
        Set<String> references = new LinkedHashSet<>();
        references.add(buildFullTableName(databaseName, schemaName, tableName));
        references.add(buildFullTableName(null, schemaName, tableName));
        references.add(quoteIdentifier(tableName));
        references.add(buildUnquotedFullTableName(databaseName, schemaName, tableName));
        references.add(buildUnquotedFullTableName(null, schemaName, tableName));
        return references.stream()
                .filter(StringUtils::isNotBlank)
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .toList();
    }

    private static String buildUnquotedFullTableName(String databaseName, String schemaName, String tableName) {
        List<String> parts = new ArrayList<>(3);
        if (StringUtils.isNotBlank(databaseName)) {
            parts.add(databaseName);
        }
        if (StringUtils.isNotBlank(schemaName)) {
            parts.add(schemaName);
        }
        if (StringUtils.isNotBlank(tableName)) {
            parts.add(tableName);
        }
        return String.join(".", parts);
    }

    static String buildFullTableName(String databaseName, String schemaName, String tableName) {
        StringBuilder fullTableName = new StringBuilder();

        if (StringUtils.isNotBlank(databaseName)) {
            fullTableName.append(quoteIdentifier(databaseName)).append('.');
        }
        if (StringUtils.isNotBlank(schemaName)) {
            fullTableName.append(quoteIdentifier(schemaName)).append('.');
        }

        if (StringUtils.isNotBlank(tableName)) {
            fullTableName.append(quoteIdentifier(tableName));
        }

        return fullTableName.toString();
    }

    private static String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        return SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(unquoteIdentifier(identifier));
    }

    static String unquoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        String value = identifier;
        if (value.length() >= 2 && value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1).replace("]]", "]");
        }
        return value;
    }

    private static final class DdlLexicalState {
        private boolean inString;
        private int blockCommentDepth;

        private boolean isCode() {
            return !inString && blockCommentDepth == 0;
        }
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        String fullTableName = buildFullTableName(databaseName, schemaName, tableName);
        return String.format(SQL_DROP_TABLE, fullTableName);
    }

    @Override
    public String truncateTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_TRUNCATE_TABLE,
                buildFullTableName(databaseName, schemaName, tableName));
    }

    @Override
    public void dropView(Connection connection, String databaseName, String schemaName, String viewName) {
        String fullTableName = buildFullTableName(databaseName, schemaName, viewName);
        String sql = String.format(SQL_DROP_VIEW, fullTableName);
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }
}

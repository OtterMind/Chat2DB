package ai.chat2db.plugin.mysql;

import ai.chat2db.plugin.mysql.converter.MysqlRoutineConverter;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;
import ai.chat2db.plugin.mysql.model.RoutineParameter;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.IRoutineManager;
import ai.chat2db.community.domain.api.enums.plugin.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.metadata.RoutineOperation;
import ai.chat2db.community.domain.api.model.sql.SqlPreview;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IResultSetFunction;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_DROP;

import static ai.chat2db.plugin.mysql.constant.MysqlRoutineManageConstants.*;
public class MysqlRoutineManager implements IRoutineManager {

    private static final String FAILURE_STAGE_BEFORE_IMAGE = "BEFORE_IMAGE";
    private static final String FAILURE_STAGE_DROP = "DROP";
    private static final String FAILURE_STAGE_APPLY = "APPLY";
    private static final String EXTRA_FAILURE_STAGE = "routineMigrationFailureStage";
    private static final String EXTRA_RESTORE_ATTEMPTED = "routineMigrationRestoreAttempted";
    private static final String EXTRA_RESTORE_SUCCEEDED = "routineMigrationRestoreSucceeded";

    private final MysqlMetaData metaData = new MysqlMetaData();
    private final MysqlRoutineConverter mysqlRoutineConverter = new MysqlRoutineConverter();

    @Override
    public SqlPreview previewInvocation(Connection connection, RoutineOperation operation) {
        String routineType = normalizeRoutineType(operation.getRoutineType());
        String routineName = requireRoutineName(operation);
        List<RoutineParameter> parameters = queryRoutineParameters(connection, operation, routineType, routineName);
        List<String> setupSql = new ArrayList<>();
        List<String> argumentSql = new ArrayList<>();
        List<String> outputVariables = new ArrayList<>();
        Map<String, Integer> variableNameCounts = new HashMap<>();
        for (RoutineParameter parameter : parameters) {
            String variable = "@" + toMysqlVariableName(parameter, argumentSql.size() + 1, variableNameCounts);
            argumentSql.add(variable);
            if (parameter.isInput()) {
                setupSql.add("set " + variable + " = " + defaultValueSql(parameter.dataType()) + ";");
            }
            if (parameter.isOutput()) {
                outputVariables.add(variable);
            }
        }

        StringBuilder sql = new StringBuilder();
        if (!setupSql.isEmpty()) {
            sql.append(String.join("\n", setupSql)).append("\n\n");
        }
        if (FUNCTION.equals(routineType)) {
            sql.append(renderRoutineInvocation("select", routineInvocationName(routineName), argumentSql));
            return SqlPreview.builder().sql(sql.toString()).build();
        }
        sql.append(renderRoutineInvocation("call", routineInvocationName(routineName), argumentSql));
        if (!outputVariables.isEmpty()) {
            sql.append("\nselect ").append(String.join(", ", outputVariables)).append(";");
        }
        return SqlPreview.builder().sql(sql.toString()).build();
    }

    @Override
    public SqlPreview previewMigration(Connection connection, RoutineOperation operation) {
        return SqlPreview.builder().sql(buildMigrationPlan(operation).previewSql()).build();
    }

    @Override
    public ExecuteResponse executeMigration(Connection connection, RoutineOperation operation) {
        RoutineMigrationPlan migrationPlan = buildMigrationPlan(operation);
        try {
            PreviousRoutineDefinition previousRoutine = capturePreviousRoutine(connection, migrationPlan);
            return replaceRoutine(connection, migrationPlan, previousRoutine);
        } catch (Exception e) {
            return migrationFailure(
                    migrationPlan,
                    "Routine migration was rejected because the existing routine definition could not be captured before DROP: "
                            + rootMessage(e),
                    FAILURE_STAGE_BEFORE_IMAGE,
                    false,
                    false);
        }
    }

    private ExecuteResponse replaceRoutine(Connection connection, RoutineMigrationPlan migrationPlan,
            PreviousRoutineDefinition previousRoutine) {
        try {
            executeMigrationStatement(connection, migrationPlan.dropSql());
        } catch (Exception e) {
            return migrationFailure(
                    migrationPlan,
                    "Routine migration failed before the previous routine was dropped. Original error: "
                            + rootMessage(e),
                    FAILURE_STAGE_DROP,
                    false,
                    false);
        }

        try {
            ExecuteResponse createResult = executeMigrationStatement(connection, migrationPlan.createSql());
            return migrationSuccess(migrationPlan, createResult);
        } catch (Exception e) {
            return handleCreateFailure(connection, migrationPlan, previousRoutine, e);
        }
    }

    private ExecuteResponse migrationSuccess(RoutineMigrationPlan migrationPlan, ExecuteResponse createResult) {
        ExecuteResponse result = createResult == null ? new ExecuteResponse() : createResult;
        result.setSuccess(Boolean.TRUE);
        result.setDescription(I18nUtils.getMessage("sqlResult.success"));
        result.setSql(migrationPlan.previewSql());
        result.setOriginalSql(migrationPlan.previewSql());
        result.setSqlType(SqlTypeEnum.UNKNOWN.name());
        return result;
    }

    private RoutineMigrationPlan buildMigrationPlan(RoutineOperation operation) {
        String routineType = normalizeRoutineType(operation.getRoutineType());
        String databaseName = requireDatabaseName(operation);
        String routineName = requireRoutineName(operation);
        String ddl = ensureSqlEndsWithSemicolon(operation.getDdl());
        String qualifiedName = mysqlQualifiedName(databaseName, routineName);
        String dropSql = SQL_DROP + routineType + " IF EXISTS " + qualifiedName;
        return new RoutineMigrationPlan(
                routineType,
                databaseName,
                routineName,
                qualifiedName,
                dropSql,
                ddl);
    }

    private PreviousRoutineDefinition capturePreviousRoutine(Connection connection, RoutineMigrationPlan migrationPlan) {
        if (!routineExists(connection, migrationPlan)) {
            return PreviousRoutineDefinition.missing();
        }

        String ddl = showCreateRoutine(connection, migrationPlan);
        if (StringUtils.isBlank(ddl)) {
            throw new IllegalStateException("Existing routine definition is empty");
        }
        return PreviousRoutineDefinition.existing(
                ensureSqlEndsWithSemicolon(qualifyCreateRoutineDdl(ddl, migrationPlan)));
    }

    private ExecuteResponse handleCreateFailure(Connection connection, RoutineMigrationPlan migrationPlan,
            PreviousRoutineDefinition previousRoutine,
            Exception createException) {
        if (!previousRoutine.exists()) {
            return migrationFailure(
                    migrationPlan,
                    "Routine migration failed. No previous routine definition existed. Original error: "
                            + rootMessage(createException),
                    FAILURE_STAGE_APPLY,
                    false,
                    false);
        }

        RestoreResult restoreResult = restorePreviousRoutine(connection, migrationPlan, previousRoutine);
        if (restoreResult.success()) {
            return migrationFailure(
                    migrationPlan,
                    "Routine migration failed. The previous routine definition was restored. Original error: "
                            + rootMessage(createException),
                    FAILURE_STAGE_APPLY,
                    true,
                    true);
        }

        return migrationFailure(
                migrationPlan,
                "Routine migration failed after the previous routine was dropped, and automatic restore failed. Original error: "
                        + rootMessage(createException) + "; restore error: " + restoreResult.message(),
                FAILURE_STAGE_APPLY,
                true,
                false);
    }

    private RestoreResult restorePreviousRoutine(Connection connection, RoutineMigrationPlan migrationPlan,
            PreviousRoutineDefinition previousRoutine) {
        try {
            executeMigrationStatement(connection, migrationPlan.dropSql());
            executeMigrationStatement(connection, previousRoutine.ddl());
            return RestoreResult.succeeded();
        } catch (Exception e) {
            return RestoreResult.failed(rootMessage(e));
        }
    }

    private ExecuteResponse migrationFailure(RoutineMigrationPlan migrationPlan, String message, String failureStage,
            boolean restoreAttempted, boolean restoreSucceeded) {
        Map<String, Object> extra = new HashMap<>();
        extra.put(EXTRA_FAILURE_STAGE, failureStage);
        extra.put(EXTRA_RESTORE_ATTEMPTED, restoreAttempted);
        extra.put(EXTRA_RESTORE_SUCCEEDED, restoreSucceeded);
        return ExecuteResponse.builder()
                .success(Boolean.FALSE)
                .message(message)
                .description(message)
                .sql(migrationPlan.previewSql())
                .originalSql(migrationPlan.previewSql())
                .sqlType(SqlTypeEnum.UNKNOWN.name())
                .extra(extra)
                .build();
    }

    private ExecuteResponse executeMigrationStatement(Connection connection, String statement) throws Exception {
        ExecuteResponse result = executeStatement(connection, statement);
        if (result != null) {
            result.setOriginalSql(statement);
            result.setSql(statement);
        }
        return result;
    }

    ExecuteResponse executeStatement(Connection connection, String statement) throws Exception {
        return DefaultSQLExecutor.getInstance().execute(SqlStatementExecuteRequest.builder()
                .sql(statement)
                .connection(connection)
                .limitRowSize(true)
                .build());
    }

    boolean routineExists(Connection connection, RoutineMigrationPlan migrationPlan) {
        IResultSetFunction<Boolean> existsReader = resultSet -> resultSet.next();
        Boolean exists = DefaultSQLExecutor.getInstance().preExecute(
                connection,
                """
                        SELECT 1
                        FROM information_schema.routines
                        WHERE ROUTINE_SCHEMA = ?
                          AND ROUTINE_NAME = ?
                          AND ROUTINE_TYPE = ?
                        LIMIT 1
                        """,
                new String[]{migrationPlan.databaseName(), migrationPlan.routineName(), migrationPlan.routineType()},
                existsReader);
        return Boolean.TRUE.equals(exists);
    }

    String showCreateRoutine(Connection connection, RoutineMigrationPlan migrationPlan) {
        return DefaultSQLExecutor.getInstance().execute(
                connection,
                "SHOW CREATE " + migrationPlan.routineType() + " " + migrationPlan.qualifiedName(),
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    return resultSet.getString("Create " + toTitleCase(migrationPlan.routineType()));
                });
    }

    private String qualifyCreateRoutineDdl(String ddl, RoutineMigrationPlan migrationPlan) {
        Pattern routineNamePattern = Pattern.compile(
                "(?is)\\b" + migrationPlan.routineType()
                        + "\\s+(`(?:``|[^`])+`|[A-Za-z0-9_$]+)(?:\\s*\\.\\s*(`(?:``|[^`])+`|[A-Za-z0-9_$]+))?");
        Matcher matcher = routineNamePattern.matcher(ddl);
        if (!matcher.find()) {
            throw new IllegalStateException("Existing routine definition does not contain routine name");
        }
        if (matcher.group(2) != null) {
            return ddl;
        }
        return ddl.substring(0, matcher.start(1)) + migrationPlan.qualifiedName() + ddl.substring(matcher.end(1));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StringUtils.defaultIfBlank(current.getMessage(), current.getClass().getSimpleName());
    }

    private String toTitleCase(String routineType) {
        String normalized = StringUtils.trimToEmpty(routineType).toLowerCase(Locale.ROOT);
        if (StringUtils.isBlank(normalized)) {
            return normalized;
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private List<RoutineParameter> queryRoutineParameters(Connection connection, RoutineOperation operation,
                                                          String routineType, String routineName) {
        String databaseName = requireDatabaseName(operation);
        try {
            if (FUNCTION.equals(routineType)) {
                return metaData.getFunctionParameters(connection, databaseName, StringUtils.trimToNull(operation.getSchemaName()),
                                routineName)
                        .stream()
                        .map(mysqlRoutineConverter::functionParameter2routineParameter)
                        .filter(Objects::nonNull)
                        .sorted((left, right) -> Integer.compare(left.ordinalPosition(), right.ordinalPosition()))
                        .toList();
            }

            return metaData.getProcedureParameters(connection, databaseName, StringUtils.trimToNull(operation.getSchemaName()),
                            routineName)
                    .stream()
                    .map(mysqlRoutineConverter::procedureParameter2routineParameter)
                    .filter(Objects::nonNull)
                    .sorted((left, right) -> Integer.compare(left.ordinalPosition(), right.ordinalPosition()))
                    .toList();
        } catch (Exception e) {
            throw new BusinessException("routine.operation.parameterLoadFailed", new Object[]{e.getMessage()}, e);
        }
    }

    private String normalizeRoutineType(String routineType) {
        String normalized = StringUtils.trimToEmpty(routineType).toUpperCase(Locale.ROOT);
        if (!FUNCTION.equals(normalized) && !PROCEDURE.equals(normalized)) {
            throw new BusinessException("routine.operation.typeUnsupported");
        }
        return normalized;
    }

    private String requireRoutineName(RoutineOperation operation) {
        String routineName = StringUtils.trimToEmpty(operation.getRoutineName());
        if (StringUtils.isBlank(routineName)) {
            throw new BusinessException("routine.operation.nameRequired");
        }
        return routineName;
    }

    private String requireDatabaseName(RoutineOperation operation) {
        String databaseName = StringUtils.trimToEmpty(operation.getDatabaseName());
        if (StringUtils.isBlank(databaseName)) {
            throw new BusinessException("routine.operation.databaseRequired");
        }
        return databaseName;
    }

    private String ensureSqlEndsWithSemicolon(String sql) {
        String trimmed = StringUtils.trimToEmpty(sql);
        if (StringUtils.isBlank(trimmed)) {
            throw new BusinessException("routine.operation.ddlRequired");
        }
        return trimmed.endsWith(";") ? trimmed : trimmed + ";";
    }

    private String mysqlQualifiedName(String databaseName, String routineName) {
        return Arrays.asList(databaseName, routineName).stream()
                .filter(StringUtils::isNotBlank)
                .map(this::quoteMysqlIdentifier)
                .reduce((left, right) -> left + "." + right)
                .orElseThrow(() -> new BusinessException("routine.operation.nameRequired"));
    }

    private String quoteMysqlIdentifier(String name) {
        return MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(name);
    }

    private String routineInvocationName(String name) {
        String trimmed = StringUtils.trimToEmpty(name);
        if (trimmed.matches("^[A-Za-z_][A-Za-z0-9_$]*$")) {
            return trimmed;
        }
        return quoteMysqlIdentifier(trimmed);
    }

    private String renderRoutineInvocation(String command, String qualifiedName, List<String> arguments) {
        if (arguments.isEmpty()) {
            return command + " " + qualifiedName + "();";
        }

        StringBuilder sql = new StringBuilder(command).append(" ").append(qualifiedName).append("(\n");
        for (int i = 0; i < arguments.size(); i++) {
            sql.append("    ").append(arguments.get(i));
            if (i < arguments.size() - 1) {
                sql.append(",");
            }
            sql.append("\n");
        }
        return sql.append(");").toString();
    }

    private String toMysqlVariableName(RoutineParameter parameter, int index, Map<String, Integer> variableNameCounts) {
        String parameterName = StringUtils.defaultIfBlank(parameter.name(), "p" + index);
        String normalized = parameterName.replaceAll("[^A-Za-z0-9_]", "_");
        if (StringUtils.isBlank(normalized) || !Character.isLetter(normalized.charAt(0))) {
            normalized = "p_" + index;
        }
        int count = variableNameCounts.getOrDefault(normalized, 0) + 1;
        variableNameCounts.put(normalized, count);
        return count == 1 ? normalized : normalized + "_" + count;
    }

    private String defaultValueSql(String dataType) {
        String normalized = StringUtils.trimToEmpty(dataType).toUpperCase(Locale.ROOT);
        if (List.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT", "DECIMAL", "NUMERIC",
                "FLOAT", "DOUBLE", "REAL", "BIT", "BOOL", "BOOLEAN").contains(normalized)) {
            return "0";
        }
        if (List.of("CHAR", "VARCHAR", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT", "ENUM", "SET").contains(
                normalized)) {
            return "''";
        }
        if ("DATE".equals(normalized)) {
            return "CURRENT_DATE";
        }
        if ("TIME".equals(normalized)) {
            return "CURRENT_TIME";
        }
        if (List.of("DATETIME", "TIMESTAMP").contains(normalized)) {
            return "CURRENT_TIMESTAMP";
        }
        if ("YEAR".equals(normalized)) {
            return "YEAR(CURRENT_DATE)";
        }
        if ("JSON".equals(normalized)) {
            return "'{}'";
        }
        if (List.of("BINARY", "VARBINARY", "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB").contains(normalized)) {
            return "X''";
        }
        return "NULL";
    }

    private record PreviousRoutineDefinition(boolean exists, String ddl) {
        private static PreviousRoutineDefinition missing() {
            return new PreviousRoutineDefinition(false, null);
        }

        private static PreviousRoutineDefinition existing(String ddl) {
            return new PreviousRoutineDefinition(true, ddl);
        }
    }

    record RoutineMigrationPlan(String routineType, String databaseName, String routineName,
                                String qualifiedName, String dropSql, String createSql) {
        private String previewSql() {
            return dropSql + ";\n\n" + createSql;
        }
    }

    private record RestoreResult(boolean success, String message) {
        private static RestoreResult succeeded() {
            return new RestoreResult(true, null);
        }

        private static RestoreResult failed(String message) {
            return new RestoreResult(false, message);
        }
    }
}

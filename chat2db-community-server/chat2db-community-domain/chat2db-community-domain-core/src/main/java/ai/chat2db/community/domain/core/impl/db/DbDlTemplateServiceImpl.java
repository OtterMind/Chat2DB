package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.spi.DefaultSqlSyntaxHandler;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.request.db.DbCopyInValuesRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlCountRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbExecuteResultEnhanceRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlValidateRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSelectResultUpdateRequest;
import ai.chat2db.community.domain.api.service.db.IDbDlTemplateService;
import ai.chat2db.community.domain.core.converter.CommandConverter;
import ai.chat2db.community.tools.enums.DataSourceTypeEnum;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.EasyCollectionUtils;
import ai.chat2db.spi.ICommandExecutor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionOperation;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.SqlUtils;
import com.alibaba.druid.DbType;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;


@Slf4j
@Service
public class DbDlTemplateServiceImpl implements IDbDlTemplateService {

    private final ExecuteResultHeaderEnhancer executeResultHeaderEnhancer;

    private final CommandConverter commandConverter;

    private final SqlExecutionPolicyManager sqlExecutionPolicyManager;

    public DbDlTemplateServiceImpl(ExecuteResultHeaderEnhancer executeResultHeaderEnhancer,
            CommandConverter commandConverter, SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        this.executeResultHeaderEnhancer = executeResultHeaderEnhancer;
        this.commandConverter = commandConverter;
        this.sqlExecutionPolicyManager = sqlExecutionPolicyManager;
    }

    private static final String LINE_SEPARATOR = "\r|\n|\r\n";

    @Override
    public List<ExecuteResponse> execute(DbDlExecuteRequest param) {
        if (StringUtils.isBlank(param.getSql())) {
            return Collections.emptyList();
        }
        long s1 = System.currentTimeMillis();
        ICommandExecutor executor = Chat2DBContext.getDbMetaData().getCommandExecutor();
        SqlExecutionPlan executionPlan = sqlExecutionPolicyManager.plan(executionContext(param));
        SqlExecuteRequest command = commandConverter.param2model(param);
        command.setScript(executionPlan.getSql());
        sqlExecutionPolicyManager.applyMaxRows(command, executionPlan);
        sqlExecutionPolicyManager.beforeExecute(executionPlan);
        List<ExecuteResponse> results = executor.execute(command);
        long s2 = System.currentTimeMillis();
        log.info("execute_sql cost time:{}", s2 - s1);
        List<ExecuteResponse> r = reBuildHeader(results, param.getDataSourceId(), param.getSchemaName(),
                param.getDatabaseName());
        log.info("execute_header cost time:{}", System.currentTimeMillis() - s2);
        return sqlExecutionPolicyManager.filterResultColumns(executionPlan, r);
    }

    @Override
    public ExecuteResponse executeDdl(DbDlExecuteRequest param) {
        if (Chat2DBContext.getConnection() == null) {
            throw new BusinessException("connection error");
        }
        List<ExecuteResponse> result = execute(param);
        if (CollectionUtils.isEmpty(result)) {
            return null;
        }
        for (ExecuteResponse executeResult : result) {
            if (!Boolean.TRUE.equals(executeResult.getSuccess())) {
                return executeResult;
            }
        }
        ExecuteResponse executeResult = result.get(0);
        if (StringUtils.isBlank(param.getTableName())) {
            executeResult.setTableName(SqlUtils.extractTableName(executeResult.getOriginalSql()));
        }
        return executeResult;
    }

    @Override
    public ExecuteResponse executeUpdate(DbDlExecuteRequest param) {
        SqlExecutionPlan executionPlan = sqlExecutionPolicyManager.plan(executionContext(param));
        param.setSql(executionPlan.getSql());
        sqlExecutionPolicyManager.beforeExecute(executionPlan);
        String type = Chat2DBContext.getConnectInfo().getDbType();
        if ("REDIS".equalsIgnoreCase(type)) {
            return redisExecuteUpdate(param.getSql());
        } else if ("MONGODB".equalsIgnoreCase(type)) {
            return mongodbExecuteUpdate(param.getSql());
        } else {
            return jdbcExecuteUpdate(param, type);
        }
    }

    @Override
    public List<ExecuteResponse> executeSelectTable(DbDlExecuteRequest param) {
        String type = Chat2DBContext.getConnectInfo().getDbType();
        String querySql = tableQuerySql(param, type);
        SqlExecutionPlan executionPlan = sqlExecutionPolicyManager.plan(executionContext(param.getDataSourceId(),
                param.getDatabaseName(), param.getSchemaName(), param.getTableName(), querySql));
        SqlExecuteRequest command = commandConverter.param2model(param);
        command.setScript(executionPlan.getSql());
        command.setSingle(true);
        sqlExecutionPolicyManager.applyMaxRows(command, executionPlan);
        sqlExecutionPolicyManager.beforeExecute(executionPlan);
        ICommandExecutor executor = Chat2DBContext.getDbMetaData().getCommandExecutor();
        List<ExecuteResponse> results;
        if (isDocumentOrKeyValueStore(type)) {
            if (!Objects.equals(querySql, executionPlan.getSql())) {
                throw new IllegalStateException("SQL rewrite is not supported for " + type + " table browsing");
            }
            results = executor.executeSelectTable(command);
        } else {
            results = executor.execute(command);
        }
        List<ExecuteResponse> rebuilt = reBuildHeader(results, param.getDataSourceId(), param.getSchemaName(),
                param.getDatabaseName());
        return sqlExecutionPolicyManager.filterResultColumns(executionPlan, rebuilt);
    }


    @Override
    public Long count(DbDlCountRequest param) {
        String sql = param.getSql();
        if (StringUtils.isBlank(sql)) {
            return 0L;
        }
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        SqlExecutionPlan executionPlan = sqlExecutionPolicyManager.plan(executionContext(param.getDataSourceId(),
                param.getDatabaseName(), connectInfo == null ? null : connectInfo.getSchemaName(),
                param.getTableName(), sql));
        sql = executionPlan.getSql();
        sqlExecutionPolicyManager.beforeExecute(executionPlan);
        String dataBaseType = connectInfo.getDbType();
        if (DataSourceTypeEnum.MONGODB.getCode().equalsIgnoreCase(dataBaseType)) {
            if (!Objects.equals(param.getSql(), sql)) {
                throw new IllegalStateException("SQL rewrite is not supported for MONGODB counts");
            }
            ICommandExecutor executor = Chat2DBContext.getDbMetaData().getCommandExecutor();
            return sqlExecutionPolicyManager.limitCount(executionPlan,
                    getCountOfMongodb(param.getTableName(), executor));
        }
        ICommandExecutor executor = Chat2DBContext.getDbMetaData().getCommandExecutor();
        try {
            String countSql = SqlUtils.count(sql, dataBaseType);
            if (countSql == null) {
                Long count = executor.count(sql, Chat2DBContext.getConnection());
                return sqlExecutionPolicyManager.limitCount(executionPlan, count);
            }
            ExecuteResponse executeResult = executor.execute(SqlStatementExecuteRequest.builder()
                    .sql(countSql)
                    .connection(Chat2DBContext.getConnection())
                    .limitRowSize(true)
                    .build());
            List<List<String>> dataList = executeResult.getDisplayDataList();
            if (CollectionUtils.isEmpty(dataList)) {
                return 0L;
            }
            String count = EasyCollectionUtils.stream(dataList)
                    .findFirst()
                    .orElse(Collections.emptyList())
                    .stream()
                    .findFirst()
                    .orElse("0");
            return sqlExecutionPolicyManager.limitCount(executionPlan, Long.parseLong(count));
        } catch (Exception e) {
            log.warn("Failed to execute SQL: {}", sql, e);
            try {
                Long count = executor.count(sql, Chat2DBContext.getConnection());
                return sqlExecutionPolicyManager.limitCount(executionPlan, count);
            } catch (Exception e1) {
                throw new BusinessException("count error", new Object[]{sql, e1.getMessage()}, e1);
            }
        }
    }

    @Override
    public String updateSelectResult(DbSelectResultUpdateRequest param) {
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        QueryResponse queryResult = commandConverter.updateSelectResult2query(param);
        return sqlBuilder.dml().buildByQueryResult(queryResult);
    }

    @Override
    public String copySelectResult(DbSelectResultUpdateRequest param) {

        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        QueryResponse queryResult = commandConverter.updateSelectResult2query(param);
        return sqlBuilder.dml().buildCopyByQueryResult(queryResult);
    }

    @Override
    public String copyInValues(DbCopyInValuesRequest param) {
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        if (!(sqlBuilder instanceof DefaultSqlBuilder)) {
            throw new BusinessException("copyInValues.unsupportedDataSource");
        }
        DefaultSqlBuilder defaultSqlBuilder = (DefaultSqlBuilder) sqlBuilder;
        if (StringUtils.equalsIgnoreCase(DbCopyInValuesRequest.SOURCE_TYPE_EXTERNAL_TEXT, param.getSourceType())) {
            return defaultSqlBuilder.copyExternalTextInValues(param.getExternalValues());
        }
        if (!StringUtils.equalsIgnoreCase(DbCopyInValuesRequest.SOURCE_TYPE_RESULT_SET, param.getSourceType())) {
            throw new BusinessException("copyInValues.invalidSourceType");
        }
        QueryResponse queryResult = commandConverter.copyInValues2query(param);
        return defaultSqlBuilder.copyInValuesByQuery(queryResult);
    }

    @Override
    public ExecuteResponse validate(DbSqlValidateRequest param) {
        SqlExecutionPlan executionPlan = sqlExecutionPolicyManager.plan(executionContext(param.getDataSourceId(),
                param.getDatabaseName(), param.getSchemaName(), null, param.getSql()));
        String sql = executionPlan.getSql();
        ICommandExecutor executor = Chat2DBContext.getDbMetaData().getCommandExecutor();
        if (StringUtils.isEmpty(sql)) {
            return ExecuteResponse.builder().success(true).build();
        }
        String type = Chat2DBContext.getConnectInfo().getDbType();
        List<SimpleSqlStatement> statements = new ArrayList<>();
        try {
            List<ai.chat2db.community.domain.api.model.parser.statement.Statement> antlrSqlStatements = DefaultSqlSyntaxHandler.simpleParserStatements(sql, type);
            if (CollectionUtils.isNotEmpty(antlrSqlStatements)) {
                statements = antlrSqlStatements.stream().map(statement -> {
                    SimpleSqlStatement simpleSqlStatement = new SimpleSqlStatement();
                    simpleSqlStatement.setSqlType(statement.getType());
                    simpleSqlStatement.setSql(statement.getSql());
                    return simpleSqlStatement;
                }).toList();
            }

        } catch (Exception e) { // impl-contract: fallback - validation falls back to JSqlParser when Antlr parser cannot parse SQL.
            try {
                Statements jSqlStatements = CCJSqlParserUtil.parseStatements(sql);
                List<Statement> statementList = jSqlStatements.getStatements();
                if (CollectionUtils.isNotEmpty(statementList)) {
                    statements = statementList.stream().map(
                            statement -> {
                                SimpleSqlStatement simpleSqlStatement = new SimpleSqlStatement();
                                simpleSqlStatement.setSql(statement.toString());
                                if (statement instanceof Select) {
                                    simpleSqlStatement.setSqlType(SqlTypeEnum.SELECT.name());
                                }
                                return simpleSqlStatement;
                            }
                    ).toList();
                }
            } catch (JSQLParserException ex) { // impl-contract: fallback - invalid or unrecognized SQL is reported through validation response.
                log.warn(" sql parser error ");
                if (DatabaseTypeEnum.CLICKHOUSE.name().equalsIgnoreCase(type)) {
                    boolean query = executor.isQueryCommand(Chat2DBContext.getConnection(), sql);
                    if (query) {
                        SimpleSqlStatement simpleSqlStatement = new SimpleSqlStatement();
                        simpleSqlStatement.setSqlType(SqlTypeEnum.SELECT.name());
                        simpleSqlStatement.setSql(sql);
                        statements.add(simpleSqlStatement);
                    } else {
                        log.warn("clickhouse sql parser error, but is not select sql");
                        return ExecuteResponse.builder().success(true).build();
                    }
                } else {
                    return ExecuteResponse.builder().success(true).build();
                }
            }
        }

        if (CollectionUtils.isEmpty(statements)) {
            log.warn(" can not parser any valid sql error ");
            return ExecuteResponse.builder().success(false).message("can not parser any valid sql error ").build();
        }
        if (statements.size() > 1) {
            log.info(" found {} sql statements ", statements.size());
            return ExecuteResponse.builder().success(true).build();
        }
        SimpleSqlStatement statement = statements.get(0);
        if (SqlTypeEnum.SELECT.name().equalsIgnoreCase(statement.getSqlType())) {

            try {
                Integer policyMaxRows = executionPlan.getMaxRows();
                int validationMaxRows = policyMaxRows == null ? 1000 : Math.min(1000, policyMaxRows);
                sqlExecutionPolicyManager.beforeExecute(executionPlan);
                ExecuteResponse executeResult = executor.execute(SqlStatementExecuteRequest.builder()
                        .sql(statement.getSql())
                        .connection(Chat2DBContext.getConnection())
                        .limitRowSize(true)
                        .offset(0)
                        .count(validationMaxRows)
                        .build());
                sqlExecutionPolicyManager.filterResultColumns(executionPlan, List.of(executeResult));
                return executeResult;
            } catch (Exception e) { // impl-contract: fallback - validation query failure is returned as failed ExecuteResponse.
                log.error("validate error", e);
                return ExecuteResponse.builder().success(false).message(e.getMessage()).build();
            }
        }
        return ExecuteResponse.builder().success(true).build();
    }

    private List<ExecuteResponse> reBuildHeader(List<ExecuteResponse> results, Long dataSourceId, String schemaName,
                                                    String databaseName) {
        for (ExecuteResponse executeResult : results) {
            DbExecuteResultEnhanceRequest enhanceExecuteResultRequest = new DbExecuteResultEnhanceRequest();
            enhanceExecuteResultRequest.setExecuteResult(executeResult);
            enhanceExecuteResultRequest.setDataSourceId(dataSourceId);
            enhanceExecuteResultRequest.setDatabaseName(databaseName);
            enhanceExecuteResultRequest.setSchemaName(schemaName);
            executeResultHeaderEnhancer.enhance(enhanceExecuteResultRequest);
        }
        return results;
    }

    private SqlExecutionContext executionContext(DbDlExecuteRequest request) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return new SqlExecutionContext(
                connectInfo == null ? request.getDataSourceId() : connectInfo.getDataSourceId(),
                connectInfo == null ? null : connectInfo.getDbType(),
                request.getDatabaseName(), request.getSchemaName(), request.getTableName(), request.getSql(),
                SqlExecutionOperation.EXECUTE, null, request.getApplyId());
    }

    private SqlExecutionContext executionContext(Long dataSourceId, String databaseName, String schemaName,
            String tableName, String sql) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return new SqlExecutionContext(
                connectInfo == null ? dataSourceId : connectInfo.getDataSourceId(),
                connectInfo == null ? null : connectInfo.getDbType(), databaseName,
                schemaName, tableName, sql,
                SqlExecutionOperation.EXECUTE, null);
    }

    private String tableQuerySql(DbDlExecuteRequest param, String type) {
        if (isDocumentOrKeyValueStore(type)) {
            return "SELECT * FROM " + param.getTableName();
        }
        return Chat2DBContext.getSqlBuilder().dql().buildSelectTable(param.getDatabaseName(),
                param.getSchemaName(), param.getTableName());
    }

    private boolean isDocumentOrKeyValueStore(String type) {
        return DataSourceTypeEnum.MONGODB.getCode().equalsIgnoreCase(type)
                || DataSourceTypeEnum.REDIS.getCode().equalsIgnoreCase(type);
    }

    private ExecuteResponse redisExecuteUpdate(String command) {
        ExecuteResponse executeResult = null;
        try {
            for (String originalSql : command.split(LINE_SEPARATOR)) {
                String sql = originalSql.trim();
                if (StringUtils.isBlank(sql) || "MULTI".equalsIgnoreCase(sql) || "EXEC".equalsIgnoreCase(sql)) {
                    continue;
                }
                executeResult = Chat2DBContext.getDbMetaData().getCommandExecutor()
                        .executeUpdate(originalSql, Chat2DBContext.getConnection(), 1);
            }
        } catch (Exception e) {
            log.error("executeUpdate error", e);
            throw new BusinessException("connection error", new Object[]{e.getMessage()}, e);
        }
        return executeResult;
    }

    private ExecuteResponse mongodbExecuteUpdate(String sql) {
        Connection connection = Chat2DBContext.getConnection();
        ICommandExecutor executor = Chat2DBContext.getDbMetaData().getCommandExecutor();
        ExecuteResponse executeResult;
        try {
            connection.setAutoCommit(false);
            executeResult = executor.executeUpdate(sql, connection, 1);
            connection.commit();
        } catch (Exception e) {
            log.error("executeUpdate error", e);
            throw new BusinessException("connection error", new Object[]{e.getMessage()}, e);
        }
        return executeResult;
    }

    private ExecuteResponse jdbcExecuteUpdate(DbDlExecuteRequest param, String type) {
        Connection connection = Chat2DBContext.getConnection();
        ICommandExecutor executor = Chat2DBContext.getDbMetaData().getCommandExecutor();
        ExecuteResponse executeResult = null;
        DbType dbType =
                JdbcUtils.parse2DruidDbType(type);
        List<String> sqlList = SqlUtils.parse(param.getSql(), dbType, true);
        try {
            for (String originalSql : sqlList) {
                executeResult = executor.executeUpdate(originalSql, connection, 1);
            }
        } catch (Exception e) {
            log.error("executeUpdate error", e);
            throw new BusinessException("connection error", new Object[]{e.getMessage()}, e);
        }
        return executeResult;
    }

    static Long getCountOfMongodb(String tableName, ICommandExecutor executor) {
        try {
            SqlExecuteRequest command = new SqlExecuteRequest();
            command.setTableName(tableName);
            List<ExecuteResponse> results = executor.executeSelectTable(command);
            if (CollectionUtils.isEmpty(results)) {
                return 0L;
            }
            return (long) CollectionUtils.size(results.get(0).getDataList());
        } catch (RuntimeException e) {
            throw new BusinessException("mongodb count error", new Object[]{tableName, e.getMessage()}, e);
        }
    }

}

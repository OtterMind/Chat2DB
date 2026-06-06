package ai.chat2db.server.domain.core.impl;

import ai.chat2db.server.domain.api.param.*;
import ai.chat2db.server.domain.api.param.operation.OperationLogCreateParam;
import ai.chat2db.server.domain.api.service.DlTemplateService;
import ai.chat2db.server.domain.api.service.OperationLogService;
import ai.chat2db.server.domain.api.service.TableService;
import ai.chat2db.server.domain.core.converter.CommandConverter;
import ai.chat2db.server.domain.core.util.MetaNameUtils;
import ai.chat2db.server.tools.base.excption.BusinessException;
import ai.chat2db.server.tools.common.util.EasyCollectionUtils;
import ai.chat2db.spi.CommandExecutor;
import ai.chat2db.spi.SqlBuilder;
import ai.chat2db.spi.ValueHandler;
import ai.chat2db.spi.model.*;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectInfo;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.SqlUtils;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.PagerUtils;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author moji
 * @version DataSourceCoreServiceImpl.java, v 0.1 2022年09月23日 15:51 moji Exp $
 * @date 2022/09/23
 */
@Slf4j
@Service
public class DlTemplateServiceImpl implements DlTemplateService {

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private TableService tableService;

    @Autowired
    private CommandConverter commandConverter;

    @Override
    public List<ExecuteResult> execute(DlExecuteParam param) {
        CommandExecutor executor = Chat2DBContext.getMetaData().getCommandExecutor();
        Command command = commandConverter.param2model(param);
        List<ExecuteResult> results = executor.execute(command);
        for (ExecuteResult executeResult : results) {
            List<Header> headers = executeResult.getHeaderList();
            if (executeResult.getSuccess() && executeResult.isCanEdit() && CollectionUtils.isNotEmpty(headers)) {
                headers = setColumnInfo(headers, executeResult.getTableName(), param.getSchemaName(),
                        param.getDatabaseName());
                executeResult.setHeaderList(headers);
            }
            addOperationLog(executeResult);
        }
        return results;
    }

    @Override
    public ExecuteResult executeUpdate(DlExecuteParam param) {
        CommandExecutor executor = Chat2DBContext.getMetaData().getCommandExecutor();
        ExecuteResult result = new ExecuteResult();
        result.setSuccess(true);
        DbType dbType =
                JdbcUtils.parse2DruidDbType(Chat2DBContext.getConnectInfo().getDbType());
        List<String> sqlList = SqlUtils.parse(param.getSql(), dbType);
        Connection connection = Chat2DBContext.getConnection();
        try {
            connection.setAutoCommit(false);
            for (String originalSql : sqlList) {
                ExecuteResult executeResult = executor.executeUpdate(originalSql, connection, 1);
                result = executeResult;
                addOperationLog(executeResult);
            }
            connection.commit();
        } catch (Exception e) {
            log.error("executeUpdate error", e);
            result.setSuccess(false);
            result.setMessage(e.getMessage());
        }
        return result;
    }




    @Override
    public Long count(DlCountParam param) {
        if (StringUtils.isBlank(param.getSql())) {
            return 0L;
        }
        DbType dbType =
                JdbcUtils.parse2DruidDbType(Chat2DBContext.getConnectInfo().getDbType());
        String sql = param.getSql();
        // 解析sql分页
        SQLStatement sqlStatement = SQLUtils.parseSingleStatement(sql, dbType);
        if (!(sqlStatement instanceof SQLSelectStatement)) {
            throw new BusinessException("dataSource.sqlAnalysisError");
        }
        sql = PagerUtils.count(sql, dbType);
        ExecuteResult executeResult;
        try {
            ValueHandler valueHandler = Chat2DBContext.getMetaData().getValueHandler();
            executeResult = Chat2DBContext.getMetaData().getCommandExecutor().execute(sql, Chat2DBContext.getConnection(), true, null, null,
                    valueHandler);
        } catch (SQLException e) {
            log.warn("执行sql:{}异常", sql, e);
            executeResult = ExecuteResult.builder()
                    .sql(sql)
                    .success(Boolean.FALSE)
                    .message(e.getMessage())
                    .build();
        }

        List<List<String>> dataList = executeResult.getDataList();
        if (CollectionUtils.isEmpty(dataList)) {
            return 0L;
        }
        String count = EasyCollectionUtils.stream(executeResult.getDataList())
                .findFirst()
                .orElse(Collections.emptyList())
                .stream()
                .findFirst()
                .orElse("0");
        return Long.valueOf(count);
    }

    @Override
    public String updateSelectResult(UpdateSelectResultParam param) {
        SqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        String sql = sqlBuilder.generateSqlBasedOnResults(param.getTableName(), param.getHeaderList(),
                param.getOperations());
        return sql;
    }

    @Override
    public String getOrderBySql(OrderByParam param) {
        SqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        String orderSql = sqlBuilder.buildOrderBySql(param.getOriginSql(), param.getOrderByList());
        return orderSql;
    }


    private List<Header> setColumnInfo(List<Header> headers, String tableName, String schemaName, String databaseName) {
        try {
            TableQueryParam tableQueryParam = new TableQueryParam();
            tableQueryParam.setTableName(MetaNameUtils.getMetaName(tableName));
            tableQueryParam.setSchemaName(schemaName);
            tableQueryParam.setDatabaseName(databaseName);
            tableQueryParam.setRefresh(true);
            tableQueryParam.setDataSourceId(Chat2DBContext.getConnectInfo().getDataSourceId());
            List<TableColumn> columns = tableService.queryColumns(tableQueryParam);
            if (CollectionUtils.isEmpty(columns)) {
                return headers;
            }
            Map<String, TableColumn> columnMap = columns.stream().collect(
                    Collectors.toMap(TableColumn::getName, tableColumn -> tableColumn));
            List<TableIndex> tableIndices = tableService.queryIndexes(tableQueryParam);
            if (!CollectionUtils.isEmpty(tableIndices)) {
                for (TableIndex tableIndex : tableIndices) {
                    if ("PRIMARY".equalsIgnoreCase(tableIndex.getType())) {
                        List<TableIndexColumn> columnList = tableIndex.getColumnList();
                        if (!CollectionUtils.isEmpty(columnList)) {
                            for (TableIndexColumn tableIndexColumn : columnList) {
                                TableColumn tableColumn = columnMap.get(tableIndexColumn.getColumnName());
                                if (tableColumn != null) {
                                    tableColumn.setPrimaryKey(true);
                                }
                            }
                        }
                    }
                }
            }
            for (Header header : headers) {
                TableColumn tableColumn = columnMap.get(header.getName());
                if (tableColumn != null) {
                    header.setPrimaryKey(tableColumn.getPrimaryKey());
                    header.setComment(tableColumn.getComment());
                    header.setDefaultValue(tableColumn.getDefaultValue());
                    header.setNullable(tableColumn.getNullable());
                    header.setColumnSize(tableColumn.getColumnSize());
                    header.setDecimalDigits(tableColumn.getDecimalDigits());
                }
            }

        } catch (Exception e) {
            log.error("setColumnInfo error:", e);
        }
        return headers;
    }


    private void addOperationLog(ExecuteResult executeResult) {
        if (executeResult == null) {
            return;
        }
        try {
            ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
            OperationLogCreateParam createParam = new OperationLogCreateParam();
            createParam.setDdl(executeResult.getSql());
            createParam.setStatus(executeResult.getSuccess() ? "success" : "fail");
            createParam.setDatabaseName(connectInfo.getDatabaseName());
            createParam.setDataSourceId(connectInfo.getDataSourceId());
            createParam.setSchemaName(connectInfo.getSchemaName());
            createParam.setUseTime(executeResult.getDuration());
            createParam.setType(connectInfo.getDbType());
            createParam.setOperationRows(
                    executeResult.getUpdateCount() != null ? Long.valueOf(executeResult.getUpdateCount()) : null);
            operationLogService.create(createParam);
        } catch (Exception e) {
            log.error("addOperationLog error:", e);
        }
    }
}

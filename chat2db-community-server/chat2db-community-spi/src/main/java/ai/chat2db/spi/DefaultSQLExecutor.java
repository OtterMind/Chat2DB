package ai.chat2db.spi;

import ai.chat2db.spi.IResultSetConsumer;
import ai.chat2db.spi.IResultSetFunction;

import ai.chat2db.community.tools.constant.IEasyToolsConstant;
import ai.chat2db.community.tools.enums.DataSourceTypeEnum;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.EasyCollectionUtils;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.ICommandExecutor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.ResultSetEditorTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.SqlTypeEnum;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.spi.model.request.FetchAllTableRecordsRequest;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import ai.chat2db.spi.model.ExecutionTiming;
import ai.chat2db.spi.model.JdbcExecutionContext;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionCancellation;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.ResultSetUtils;
import ai.chat2db.spi.util.SqlUtils;
import cn.hutool.core.date.TimeInterval;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;


@Slf4j
public class DefaultSQLExecutor implements ICommandExecutor {

    private static final int STREAMING_ROW_BATCH_SIZE = 200;


    private static final DefaultSQLExecutor INSTANCE = new DefaultSQLExecutor();

    public DefaultSQLExecutor() {
    }

    public static DefaultSQLExecutor getInstance() {
        return INSTANCE;
    }


    public <R> R execute(Connection connection, String sql, IResultSetFunction<R> function) {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            boolean query = stmt.execute();
            if (query) {
                try (ResultSet rs = stmt.getResultSet();) {
                    return function.apply(rs);
                }
            }
        } catch (Exception e) {
            log.error("execute:{}", sql, e);
            throw new RuntimeException(e);
        }
        return null;
    }

    public void execute(Connection connection, String sql, IResultSetConsumer consumer) {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            boolean query = stmt.execute();
            if (query) {
                try (ResultSet rs = stmt.getResultSet();) {
                    consumer.accept(rs);
                }
            }
        } catch (Exception e) {
            log.error("execute:{}", sql, e);
            throw new RuntimeException(e);
        }
    }

    public <R> R preExecute(Connection connection, String sql, String[] parameters, IResultSetFunction<R> function) {
        log.info("execute:{}", sql);
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                stmt.setString(i + 1, parameters[i]);
            }
            boolean query = stmt.execute();
            if (query) {
                try (ResultSet rs = stmt.getResultSet();) {
                    return function.apply(rs);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public void preExecute(Connection connection, String sql, String[] parameters, IResultSetConsumer consumer) {
        log.info("execute:{}", sql);
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                stmt.setString(i + 1, parameters[i]);
            }
            boolean query = stmt.execute();
            if (query) {
                try (ResultSet rs = stmt.getResultSet();) {
                    consumer.accept(rs);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void execute(
            Connection connection, String sql,
            Consumer<List<Header>> headerConsumer,
            Consumer<List<String>> rowConsumer,
            Function<JDBCDataValue, String> valueFunction,
            boolean limitSize) {
        execute(connection, sql, headerConsumer, rowConsumer, valueFunction, limitSize, null);
    }

    public void execute(
            Connection connection, String sql,
            Consumer<List<Header>> headerConsumer,
            Consumer<List<String>> rowConsumer,
            Function<JDBCDataValue, String> valueFunction,
            boolean limitSize,
            Integer resultSetId) {
        Assert.notNull(sql, "SQL must not be null");
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            boolean query = stmt.execute();
            int resultCount = 0;
            while (true) {
                if (query) {
                    resultCount++;
                    if (resultSetId == null || resultCount == resultSetId) {
                        writeExportResultSet(stmt, headerConsumer, rowConsumer, valueFunction, limitSize);
                        return;
                    }
                } else if (stmt.getUpdateCount() == -1) {
                    return;
                }
                query = stmt.getMoreResults();
            }
        } catch (SQLException e) {
            log.error("execute:{}", sql, e);
            throw new RuntimeException(e);
        }
    }

    private void writeExportResultSet(Statement stmt, Consumer<List<Header>> headerConsumer,
                                      Consumer<List<String>> rowConsumer,
                                      Function<JDBCDataValue, String> valueFunction,
                                      boolean limitSize) throws SQLException {
        ResultSet rs = null;
        try {
            rs = stmt.getResultSet();
            if (rs == null) {
                return;
            }
            ResultSetMetaData resultSetMetaData = rs.getMetaData();
            int col = resultSetMetaData.getColumnCount();
            List<Header> headerList = generateHeaderList(resultSetMetaData);

            int chat2dbAutoRowIdIndex = getChat2dbAutoRowIdIndex(headerList);

            headerConsumer.accept(headerList);

            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= col; i++) {
                    if (chat2dbAutoRowIdIndex == i) {
                        continue;
                    }
                    JDBCDataValue jdbcDataValue = new JDBCDataValue(rs, resultSetMetaData, i, limitSize);
                    row.add(valueFunction.apply(jdbcDataValue));
                }
                rowConsumer.accept(row);
            }
        } finally {
            JdbcUtils.closeResultSet(rs);
        }
    }

    @Override
    public ExecuteResponse executeUpdate(String sql, Connection connection, int n)
            throws SQLException {
        Assert.notNull(sql, "SQL must not be null");
        ExecuteResponse executeResult = ExecuteResponse.builder().sql(sql).success(Boolean.TRUE).build();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            int affectedRows = stmt.executeUpdate();
            if (affectedRows != n) {
                log.info("Update error {} update affectedRows = {}", sql, affectedRows);
            }
        }
        return executeResult;
    }

    @Override
    public List<ExecuteResponse> executeSelectTable(SqlExecuteRequest command) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        String tableName = metaData.getMetaDataName(command.getDatabaseName(), command.getSchemaName(),
                command.getTableName());
        String sql = "select * from " + tableName;
        command.setScript(sql);
        command.setSingle(true);
        return execute(command);
    }


    public ExecuteResponse execute(SqlStatementExecuteRequest request)
            throws SQLException {
        String sql = request.getSql();
        Connection connection = request.getConnection();
        boolean limitRowSize = request.isLimitRowSize();
        Integer offset = request.getOffset();
        Integer count = request.getCount();
        Assert.notNull(sql, "SQL must not be null");
        ExecuteResponse executeResult = ExecuteResponse.builder().sql(sql).success(Boolean.TRUE).build();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setFetchSize(IEasyToolsConstant.MAX_PAGE_SIZE);
            if (sql.toLowerCase().startsWith("select")) {
                if (offset != null && count != null) {
                    stmt.setMaxRows(offset + count);
                }
            }
            long startedAtEpochMs = System.currentTimeMillis();
            TimeInterval timeInterval = new TimeInterval();
            ExecutionContext executionContext = JdbcExecutionContext.capture(connection);
            long executeStartedNanos = System.nanoTime();
            boolean query = stmt.execute();
            long executeDurationNanos = ExecutionTiming.elapsedNanos(executeStartedNanos);
            executionContext = executionContextAfterExecute(
                    new SimpleSqlStatement(sql), connection, executionContext);
            long fetchDurationNanos = 0L;
            executeResult.setDescription(I18nUtils.getMessage("sqlResult.success"));
            if (query) {
                long fetchStartedNanos = System.nanoTime();
                executeResult = generateQueryExecuteResponse(stmt, limitRowSize, offset, count);
                fetchDurationNanos = ExecutionTiming.elapsedNanos(fetchStartedNanos);
            } else {
                executeResult.setUpdateCount(stmt.getUpdateCount());
            }
            executeResult.setDuration(timeInterval.interval());
            executeResult.setStatementSequence(1);
            executeResult.setExecutionContext(executionContext);
            executeResult.setExecutionMetrics(ExecutionTiming.complete(
                    ExecutionTiming.started(startedAtEpochMs), executeDurationNanos, fetchDurationNanos,
                    CollectionUtils.size(executeResult.getDataList())));
        }
        return executeResult;
    }

    @Override
    public Long count(String sql, Connection connection) throws SQLException {
        Assert.notNull(sql, "SQL must not be null");
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            boolean query = stmt.execute();
            if (query) {
                long n = 0;
                ResultSet rs = stmt.getResultSet();
                while (rs.next()) {
                    n++;
                }
                return n;
            } else {
                return null;
            }
        }
    }

    protected ExecuteResponse generateQueryExecuteResponse(Statement stmt, boolean limitRowSize, Integer offset,
                                                     Integer count) throws SQLException {
        ExecuteResponse executeResult = ExecuteResponse.builder().success(Boolean.TRUE).build();
        executeResult.setDescription(I18nUtils.getMessage("sqlResult.success"));
        ResultSet rs = null;
        try {
            rs = stmt.getResultSet();
            ResultSetMetaData resultSetMetaData = rs.getMetaData();
            int col = resultSetMetaData.getColumnCount();
            List<Header> headerList = generateHeaderList(resultSetMetaData);


            int chat2dbAutoRowIdIndex = getChat2dbAutoRowIdIndex(headerList);
            List<List<ResultCell>> dataList = generateDataList(rs, col, chat2dbAutoRowIdIndex, limitRowSize,
                    offset, count);

            executeResult.setHeaderList(headerList);
            executeResult.setDataList(dataList);
        } finally {
            JdbcUtils.closeResultSet(rs);
        }
        return executeResult;
    }

    private List<List<ResultCell>> generateDataList(ResultSet rs, int col, int chat2dbAutoRowIdIndex,
                                          boolean limitRowSize, Integer offset, Integer count) throws SQLException {
        List<List<ResultCell>> dataList = Lists.newArrayList();

        if (offset == null || offset < 0) {
            offset = 0;
        }
        int rowNumber = 0;
        int rowCount = 1;
        IValueProcessor valueProcessor;
        if (Chat2DBContext.getConnectInfo() != null) {
            valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        } else {
            valueProcessor = new DefaultValueProcessor();
        }
        while (rs.next()) {
            if (rowNumber++ < offset) {
                continue;
            }
            List<ResultCell> row = Lists.newArrayListWithExpectedSize(col);
            dataList.add(row);
            for (int i = 1; i <= col; i++) {
                if (chat2dbAutoRowIdIndex == i) {
                    continue;
                }
                JDBCDataValue jdbcDataValue = new JDBCDataValue(rs, rs.getMetaData(), i, limitRowSize);
                String value = valueProcessor.getJdbcValue(jdbcDataValue);
                ResultCell cell = jdbcDataValue.buildResultCell(value);
                row.add(cell);
            }
            if (count != null && count > 0 && rowCount++ >= count) {
                break;
            }
        }
        return dataList;
    }


    private int getChat2dbAutoRowIdIndex(List<Header> headerList) {

        for (int i = 0; i < headerList.size(); i++) {
            Header header = headerList.get(i);
            if ("CAHT2DB_AUTO_ROW_ID".equals(header.getName())) {
                headerList.remove(i);
                return i + 1;
            }
        }
        return -1;
    }


    private List<Header> generateHeaderList(ResultSetMetaData resultSetMetaData) throws SQLException {
        int col = resultSetMetaData.getColumnCount();
        List<Header> headerList = Lists.newArrayListWithExpectedSize(col);
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        for (int i = 1; i <= col; i++) {
            String columnTypeName = resultSetMetaData.getColumnTypeName(i);
            int columnType = resultSetMetaData.getColumnType(i);
            ResultSetEditorTypeEnum editorType = ResultSetEditorTypeEnum.from(
                    metaData.resolveResultSetEditorType(columnTypeName, columnType));
            Header header = Header.builder()
                    .dataType(JdbcUtils.resolveDataType(
                            columnTypeName, columnType).getCode())
                    .name(ResultSetUtils.getColumnName(resultSetMetaData, i))
                    .columnName(ResultSetUtils.getTableColumnName(resultSetMetaData, i))
                    .columnType(columnTypeName)
                    .editorType(editorType.getCode())
                    .build();

            try {
                header.setTableName(ResultSetUtils.getTableName(resultSetMetaData, i));
            } catch (Exception e) {
                log.error(" get table name error", e);
            }
            try {
                header.setDatabaseName(resultSetMetaData.getCatalogName(i));
            } catch (Exception e) {
                log.error(" get catalog name error", e);
            }
            try {
                header.setSchemaName(resultSetMetaData.getSchemaName(i));
            } catch (Exception e) {
                log.error(" get schema name error", e);
            }
            try {
                header.setAutoIncrement(resultSetMetaData.isAutoIncrement(i) ? 1 : 0);
            } catch (Exception e) {
                log.error(" get auto increment error", e);
            }
            headerList.add(header);
        }
        return headerList;
    }


    public ExecuteResponse execute(Connection connection, String sql) throws SQLException {
        return execute(SqlStatementExecuteRequest.builder()
                .sql(sql)
                .connection(connection)
                .limitRowSize(true)
                .build());
    }


    public List<Database> databases(Connection connection) {
        try (ResultSet resultSet = connection.getMetaData().getCatalogs();) {
            List<Database> databases = ResultSetUtils.toObjectList(resultSet, Database.class);
            if (CollectionUtils.isEmpty(databases)) {
                return databases;
            }
            return databases.stream().filter(database -> database.getName() != null).collect(Collectors.toList());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    public List<Schema> schemas(Connection connection, String databaseName, String schemaName) {
        if (StringUtils.isEmpty(databaseName) && StringUtils.isEmpty(schemaName)) {
            try (ResultSet resultSet = connection.getMetaData().getSchemas()) {
                return ResultSetUtils.toObjectList(resultSet, Schema.class);
            } catch (SQLException e) {
                throw new RuntimeException("Get schemas error", e);
            }
        }
        try (ResultSet resultSet = connection.getMetaData().getSchemas(databaseName, schemaName)) {
            return ResultSetUtils.toObjectList(resultSet, Schema.class);
        } catch (SQLException e) {
            throw new RuntimeException("Get schemas error", e);
        }
    }


    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName,
                              String types[]) {
        try (ResultSet resultSet = connection.getMetaData().getTables(databaseName, schemaName, tableName, types)) {
            return ResultSetUtils.toObjectList(resultSet, Table.class);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    public List<String> tableNames(Connection connection, String databaseName, String schemaName, String tableName,
                                   String[] types) {
        List<String> tableNames = new ArrayList<>();
        try (ResultSet resultSet = connection.getMetaData().getTables(databaseName, schemaName, tableName, types)) {
            while (resultSet.next()) {
                tableNames.add(resultSet.getString("TABLE_NAME"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return tableNames;
    }


    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String
            tableName,
                                     String columnName) {
        try (ResultSet resultSet = connection.getMetaData().getColumns(databaseName, schemaName, tableName,
                columnName)) {
            return ResultSetUtils.toObjectList(resultSet, TableColumn.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        List<TableIndex> tableIndices = Lists.newArrayList();
        try (ResultSet resultSet = connection.getMetaData().getIndexInfo(databaseName, schemaName, tableName,
                false,
                false)) {
            List<TableIndexColumn> tableIndexColumns = ResultSetUtils.toObjectList(resultSet, TableIndexColumn.class);
            tableIndexColumns.stream().filter(c -> c.getIndexName() != null).collect(
                            Collectors.groupingBy(TableIndexColumn::getIndexName)).entrySet()
                    .stream().forEach(entry -> {
                        TableIndex tableIndex = new TableIndex();
                        TableIndexColumn column = entry.getValue().get(0);
                        tableIndex.setName(entry.getKey());
                        tableIndex.setTableName(column.getTableName());
                        tableIndex.setSchemaName(column.getSchemaName());
                        tableIndex.setDatabaseName(column.getDatabaseName());
                        tableIndex.setUnique(!column.getNonUnique());
                        tableIndex.setColumnList(entry.getValue());
                        tableIndices.add(tableIndex);
                    });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return tableIndices;
    }


    public List<ai.chat2db.community.domain.api.model.metadata.Function> functions(Connection connection, String databaseName,
                                                         String schemaName) {
        try (ResultSet resultSet = connection.getMetaData().getFunctions(databaseName, schemaName, null);) {
            return ResultSetUtils.toObjectList(resultSet, ai.chat2db.community.domain.api.model.metadata.Function.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public List<Type> types(Connection connection) {
        try (ResultSet resultSet = connection.getMetaData().getTypeInfo();) {
            return ResultSetUtils.toObjectList(resultSet, ai.chat2db.community.domain.api.model.metadata.Type.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public List<Procedure> procedures(Connection connection, String databaseName, String schemaName) {
        try (ResultSet resultSet = connection.getMetaData().getProcedures(databaseName, schemaName, null)) {
            return ResultSetUtils.toObjectList(resultSet, Procedure.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getDbVersion(Connection connection) {
        try {
            String dbVersion = connection.getMetaData().getDatabaseProductVersion();
            return dbVersion;
        } catch (Exception e) {
            log.error("get db version error", e);
        }
        return "";
    }

    @Override
    public List<ExecuteResponse> execute(SqlExecuteRequest command) {
        if (StringUtils.isBlank(command.getScript())) {
            return Collections.emptyList();
        }
        String type = Chat2DBContext.getConnectInfo().getDbType();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        DbType dbType = JdbcUtils.parse2DruidDbType(type);
        Connection connection = Chat2DBContext.getConnection();
        alignConnectionContext(command, connection);
        JdbcExecutionContext.Cursor executionContextCursor = JdbcExecutionContext.cursor(
                connection, command.getDatabaseName());
        List<SimpleSqlStatement> simpleSqlStatements = buildSimpleSqlStatements(command, dbType, type, dbConfig);

        if (CollectionUtils.isEmpty(simpleSqlStatements)) {
            throw new BusinessException("dataSource.sqlAnalysisError");
        }
        List<ExecuteResponse> result = new ArrayList<>();
        Boolean errorContinue = command.getErrorContinue();
        String defaultDatabaseName = metaData.getDefaultDatabaseName(connection, command.getDatabaseName());
        String defaultSchemaName = metaData.getDefaultSchemaName(connection, command.getSchemaName());
        if (command.isExplain()) {
            setExplain(simpleSqlStatements);
        }
        int statementSequence = 0;
        for (SimpleSqlStatement simpleSqlStatement : simpleSqlStatements) {
            statementSequence++;
            String sqlType = simpleSqlStatement.getSqlType();
            List<ExecuteResponse> executeResults = executeSQL(simpleSqlStatement, dbType, command, connection,
                    executionContextCursor.current());
            advanceExecutionContext(executionContextCursor, connection, simpleSqlStatement, executeResults);
            boolean errorOccurred = false;
            for (ExecuteResponse executeResult : executeResults) {
                executeResult.setStatementSequence(statementSequence);
                executeResult.setSqlType(simpleSqlStatement.getSqlType());
                if (executeResult.getSuccess()) {
                    List<RefreshTarget> refreshTargets = simpleSqlStatement.getRefreshTargets();
                    if (CollectionUtils.isNotEmpty(refreshTargets)
                            && StringUtils.isNotBlank(sqlType)) {
                        for (RefreshTarget refreshTarget : refreshTargets) {
                            if (Objects.isNull(refreshTarget)) {
                                refreshTarget = new RefreshTarget();
                            }
                            String databaseName = refreshTarget.getDatabaseName();
                            if (StringUtils.isBlank(databaseName)) {
                                databaseName = defaultDatabaseName;
                            }
                            String schemaName = refreshTarget.getSchemaName();
                            if (StringUtils.isBlank(schemaName)) {
                                schemaName = defaultSchemaName;
                            }
                            refreshTarget.setDataSourceId(command.getDataSourceId());
                            refreshTarget.setDatabaseName(databaseName);
                            refreshTarget.setSchemaName(schemaName);
                        }
                        executeResult.setRefreshTargets(refreshTargets);
                    } else if (CollectionUtils.isEmpty(refreshTargets)
                            && StringUtils.isNotBlank(sqlType)) {
                        RefreshTarget refreshTarget = new RefreshTarget();
                        refreshTarget.setDataSourceId(command.getDataSourceId());
                        refreshTarget.setDatabaseName(command.getDatabaseName());
                        refreshTarget.setSchemaName(command.getSchemaName());
                        refreshTargets = new ArrayList<>(1);
                        refreshTargets.add(refreshTarget);
                        executeResult.setRefreshTargets(refreshTargets);
                    } else {
                        executeResult.setRefreshTargets(Collections.emptyList());
                    }
                } else {
                    errorOccurred = true;
                }
                executeResult.setComment(simpleSqlStatement.getComment());
                result.add(executeResult);
                if (Objects.nonNull(errorContinue) && !errorContinue && errorOccurred) {
                    return result;
                }
            }

        }
        return result;
    }

    public void executeStreaming(SqlExecuteRequest command, ISqlExecutionResultConsumer consumer,
                                 ISqlExecutionStatementListener statementListener,
                                 ISqlExecutionCancellation cancellation) throws SQLException {
        if (StringUtils.isBlank(command.getScript())) {
            return;
        }
        checkCanceled(cancellation);
        String type = Chat2DBContext.getConnectInfo().getDbType();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        DbType dbType = JdbcUtils.parse2DruidDbType(type);
        Connection connection = Chat2DBContext.getConnection();
        alignConnectionContext(command, connection);
        JdbcExecutionContext.Cursor executionContextCursor = JdbcExecutionContext.cursor(
                connection, command.getDatabaseName());
        List<SimpleSqlStatement> simpleSqlStatements = buildSimpleSqlStatements(command, dbType, type, dbConfig);

        if (CollectionUtils.isEmpty(simpleSqlStatements)) {
            throw new BusinessException("dataSource.sqlAnalysisError");
        }
        Boolean errorContinue = command.getErrorContinue();
        String defaultDatabaseName = metaData.getDefaultDatabaseName(connection, command.getDatabaseName());
        String defaultSchemaName = metaData.getDefaultSchemaName(connection, command.getSchemaName());
        if (command.isExplain()) {
            setExplain(simpleSqlStatements);
        }
        AtomicInteger streamResultSequence = new AtomicInteger();
        int statementSequence = 0;
        for (SimpleSqlStatement simpleSqlStatement : simpleSqlStatements) {
            statementSequence++;
            checkCanceled(cancellation);
            String sqlType = simpleSqlStatement.getSqlType();
            List<ExecuteResponse> executeResults = executeSQLStreaming(simpleSqlStatement, dbType, command, connection,
                    consumer,
                    statementListener, cancellation, streamResultSequence, statementSequence,
                    executionContextCursor.current());
            advanceExecutionContext(executionContextCursor, connection, simpleSqlStatement, executeResults);
            boolean errorOccurred = false;
            for (ExecuteResponse executeResult : executeResults) {
                executeResult.setStatementSequence(statementSequence);
                executeResult.setSqlType(simpleSqlStatement.getSqlType());
                if (executeResult.getSuccess()) {
                    List<RefreshTarget> refreshTargets = simpleSqlStatement.getRefreshTargets();
                    if (CollectionUtils.isNotEmpty(refreshTargets) && StringUtils.isNotBlank(sqlType)) {
                        for (RefreshTarget refreshTarget : refreshTargets) {
                            if (Objects.isNull(refreshTarget)) {
                                refreshTarget = new RefreshTarget();
                            }
                            String databaseName = refreshTarget.getDatabaseName();
                            if (StringUtils.isBlank(databaseName)) {
                                databaseName = defaultDatabaseName;
                            }
                            String schemaName = refreshTarget.getSchemaName();
                            if (StringUtils.isBlank(schemaName)) {
                                schemaName = defaultSchemaName;
                            }
                            refreshTarget.setDataSourceId(command.getDataSourceId());
                            refreshTarget.setDatabaseName(databaseName);
                            refreshTarget.setSchemaName(schemaName);
                        }
                        executeResult.setRefreshTargets(refreshTargets);
                    } else if (CollectionUtils.isEmpty(refreshTargets) && StringUtils.isNotBlank(sqlType)) {
                        RefreshTarget refreshTarget = new RefreshTarget();
                        refreshTarget.setDataSourceId(command.getDataSourceId());
                        refreshTarget.setDatabaseName(command.getDatabaseName());
                        refreshTarget.setSchemaName(command.getSchemaName());
                        refreshTargets = new ArrayList<>(1);
                        refreshTargets.add(refreshTarget);
                        executeResult.setRefreshTargets(refreshTargets);
                    } else {
                        executeResult.setRefreshTargets(Collections.emptyList());
                    }
                } else {
                    errorOccurred = true;
                }
                executeResult.setComment(simpleSqlStatement.getComment());
                consumer.resultFinished(executeResult);
                if (Objects.nonNull(errorContinue) && !errorContinue && errorOccurred) {
                    return;
                }
            }
        }
    }

    protected void alignConnectionContext(SqlExecuteRequest command, Connection connection) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            return;
        }
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        if (dbConfig == null || !dbConfig.isSupportDatabase()
                || StringUtils.isBlank(command.getDatabaseName())) {
            return;
        }
        Chat2DBContext.getDbManager().connectDatabase(connection, command.getDatabaseName());
    }

    protected ExecutionContext executionContextAfterExecute(SimpleSqlStatement statement, Connection connection,
                                                             ExecutionContext statementStartContext) {
        return statementStartContext;
    }

    protected void advanceExecutionContext(JdbcExecutionContext.Cursor cursor, Connection connection,
                                           SimpleSqlStatement statement, List<ExecuteResponse> executeResults) {
        boolean succeeded = CollectionUtils.isNotEmpty(executeResults) && executeResults.stream()
                .allMatch(result -> Boolean.TRUE.equals(result.getSuccess()));
        cursor.advance(connection, succeeded ? databaseContextFallback(statement) : null);
    }

    private String databaseContextFallback(SimpleSqlStatement statement) {
        if (!ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.USE_DATABASE.name()
                .equals(statement.getSqlType())) {
            return null;
        }
        return Optional.ofNullable(statement.getRefreshTargets())
                .orElseGet(Collections::emptyList)
                .stream()
                .map(RefreshTarget::getDatabaseName)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    protected List<SimpleSqlStatement> buildSimpleSqlStatements(SqlExecuteRequest command, DbType dbType, String type,
                                                                DBConfig dbConfig) {
        List<SimpleSqlStatement> simpleSqlStatements = new ArrayList<>();
        if (command.isSingle()) {
            String sql = command.getScript().trim();
            SimpleSqlStatement simpleSqlStatement;
            List<SimpleSqlStatement> sqlStatements = SqlUtils.parseStatements(sql, dbType, type);
            if (CollectionUtils.isNotEmpty(sqlStatements) && sqlStatements.size() == 1) {
                simpleSqlStatement = sqlStatements.get(0);
                simpleSqlStatement.setSql(sql);
            } else if (shouldUsePreservedCompositeStatement(command, dbConfig, sqlStatements)) {
                simpleSqlStatement = preservedCompositeStatement(sql);
            } else {
                simpleSqlStatement = new SimpleSqlStatement(sql);
            }
            simpleSqlStatements.add(simpleSqlStatement);
            return simpleSqlStatements;
        }

        if (command.getScript().length() < 50000) {
            simpleSqlStatements = SqlUtils.parseStatements(command.getScript(), dbType, type);
            if (shouldPreserveScriptBatchExecution(command, dbConfig, simpleSqlStatements)) {
                return Collections.singletonList(preservedCompositeStatement(command.getScript()));
            }
        } else {
            List<String> parse = SqlUtils.parse(command.getScript(), dbType, true);
            simpleSqlStatements = parse.stream().map(SimpleSqlStatement::new).collect(Collectors.toList());
        }
        return simpleSqlStatements;
    }

    protected boolean shouldPreserveScriptBatchExecution(SqlExecuteRequest command, DBConfig dbConfig,
                                                         List<SimpleSqlStatement> simpleSqlStatements) {
        return !command.isSingle()
                && shouldUsePreservedCompositeStatement(command, dbConfig, simpleSqlStatements);
    }

    protected boolean shouldUsePreservedCompositeStatement(SqlExecuteRequest command, DBConfig dbConfig,
                                                            List<SimpleSqlStatement> simpleSqlStatements) {
        return dbConfig != null
                && dbConfig.isPreserveScriptBatchExecution()
                && !command.isExplain()
                && CollectionUtils.size(simpleSqlStatements) > 1;
    }

    protected SimpleSqlStatement preservedCompositeStatement(String sql) {
        return new PreservedCompositeStatement(sql);
    }

    protected boolean isPreservedCompositeStatement(SimpleSqlStatement statement) {
        return statement instanceof PreservedCompositeStatement;
    }

    private static final class PreservedCompositeStatement extends SimpleSqlStatement {

        private PreservedCompositeStatement(String sql) {
            super(sql);
        }
    }

    private void setExplain(List<SimpleSqlStatement> simpleSqlStatements) {
        for (SimpleSqlStatement simpleSqlStatement : simpleSqlStatements) {
            String sql = simpleSqlStatement.getSql();
            if (StringUtils.isNotBlank(sql)) {
                String explainSql = Chat2DBContext.getSqlBuilder().dql().buildExplain(sql);
                simpleSqlStatement.setSql(explainSql);
            }
        }
    }

    private List<ExecuteResponse> executeSQL(SimpleSqlStatement simpleSqlStatement, DbType dbType,
                                              SqlExecuteRequest param, Connection connection,
                                              ExecutionContext executionContext) {
        String originalSql = simpleSqlStatement.getSql();
        long startedAtEpochMs = System.currentTimeMillis();
        long startedAtNanos = System.nanoTime();
        int pageNo = Optional.ofNullable(param.getPageNo()).orElse(1);
        int pageSize = Optional.ofNullable(param.getPageSize()).orElse(IEasyToolsConstant.MAX_PAGE_SIZE);
        Integer offset = (pageNo - 1) * pageSize;
        Integer count = pageSize;
        SqlTypeEnum sqlType = getSqlType(dbType, originalSql);
        List<ExecuteResponse> executeResults = new ArrayList<>();
        String type = simpleSqlStatement.getSqlType();
        if (SqlTypeEnum.SELECT.equals(sqlType) && !SqlUtils.hasPageLimit(originalSql, dbType)) {
            String pagingBaseSql = SqlUtils.stripTrailingSemicolon(originalSql);
            String buildPageLimit = Chat2DBContext.getSqlBuilder().dql().buildPageLimit(PageLimitRequest.builder()
                    .sql(pagingBaseSql)
                    .offset(offset)
                    .pageNo(pageNo)
                    .pageSize(pageSize)
                    .build());
            if (StringUtils.isNotBlank(buildPageLimit)) {
                try {
                    if (type == null || !StringUtils.equals(type, ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.SELECT_INTO.name())) {
                        simpleSqlStatement.setSql(buildPageLimit);
                    }
                    executeResults = executeMulti(simpleSqlStatement, connection, true, 0, count,
                            param.getResultSetId(), executionContext);
                    if (CollectionUtils.isNotEmpty(executeResults)) {
                        for (ExecuteResponse executeResult : executeResults) {
                            executeResult.setSqlType(sqlType.getCode());
                            executeResult.setOriginalSql(originalSql);
                            executeResult.setSql(buildPageLimit);
                        }
                    }
                } catch (Exception e) {
                    log.error("Execute sql: {} exception", buildPageLimit, e);
                }
            }
        }
        if (CollectionUtils.isEmpty(executeResults)) {
            try {
                simpleSqlStatement.setSql(originalSql);
                if (type != null && StringUtils.equalsAnyIgnoreCase(type, ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.MERGE.name(),
                        ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.ANONYMOUS_BLOCK.name())) {
                    if (!originalSql.endsWith(";")) {
                        simpleSqlStatement.setSql(originalSql + ";");
                    }
                }
                executeResults = executeMulti(simpleSqlStatement, connection, true, offset, count,
                        param.getResultSetId(), executionContext);
                for (ExecuteResponse executeResult : executeResults) {
                    executeResult.setSql(originalSql);
                }
            } catch (Exception ee) {
                ExecuteResponse executeResult = failedExecuteResponse(
                        originalSql, ee, startedAtEpochMs, startedAtNanos, executionContext);
                executeResults.add(executeResult);
            }
        }
        for (ExecuteResponse executeResult : executeResults) {
            executeResult.setSqlType(sqlType.getCode());
            executeResult.setOriginalSql(originalSql);

            SqlUtils.buildCanEditResult(originalSql, dbType, executeResult);
            addRowNumber(executeResult, pageNo, pageSize);
            setPageInfo(executeResult, sqlType, pageNo, pageSize);
        }

        return executeResults;
    }

    private List<ExecuteResponse> executeSQLStreaming(SimpleSqlStatement simpleSqlStatement, DbType dbType,
                                                    SqlExecuteRequest param, Connection connection,
                                                    ISqlExecutionResultConsumer consumer,
                                                    ISqlExecutionStatementListener statementListener,
                                                    ISqlExecutionCancellation cancellation,
                                                    AtomicInteger streamResultSequence,
                                                    int statementSequence,
                                                    ExecutionContext executionContext) {
        String originalSql = simpleSqlStatement.getSql();
        long startedAtEpochMs = System.currentTimeMillis();
        long startedAtNanos = System.nanoTime();
        int pageNo = Optional.ofNullable(param.getPageNo()).orElse(1);
        int pageSize = Optional.ofNullable(param.getPageSize()).orElse(IEasyToolsConstant.MAX_PAGE_SIZE);
        Integer offset = (pageNo - 1) * pageSize;
        Integer count = pageSize;
        SqlTypeEnum sqlType = getSqlType(dbType, originalSql);
        List<ExecuteResponse> executeResults = new ArrayList<>();
        String type = simpleSqlStatement.getSqlType();
        consumer.statementStarted(simpleSqlStatement.getSql(), originalSql, simpleSqlStatement.getComment());
        if (SqlTypeEnum.SELECT.equals(sqlType) && !SqlUtils.hasPageLimit(originalSql, dbType)) {
            String pagingBaseSql = SqlUtils.stripTrailingSemicolon(originalSql);
            String buildPageLimit = Chat2DBContext.getSqlBuilder().dql().buildPageLimit(PageLimitRequest.builder()
                    .sql(pagingBaseSql)
                    .offset(offset)
                    .pageNo(pageNo)
                    .pageSize(pageSize)
                    .build());
            if (StringUtils.isNotBlank(buildPageLimit)) {
                try {
                    if (type == null || !StringUtils.equals(type, ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.SELECT_INTO.name())) {
                        simpleSqlStatement.setSql(buildPageLimit);
                    }
                    executeResults = executeMultiStreaming(simpleSqlStatement, connection, true,
                            0, count, param.getResultSetId(), consumer, statementListener, cancellation,
                            sqlType, originalSql, pageNo, pageSize, streamResultSequence, statementSequence,
                            executionContext);
                    if (CollectionUtils.isNotEmpty(executeResults)) {
                        for (ExecuteResponse executeResult : executeResults) {
                            executeResult.setSqlType(sqlType.getCode());
                            executeResult.setOriginalSql(originalSql);
                            executeResult.setSql(buildPageLimit);
                        }
                    }
                } catch (Exception e) {
                    log.error("Execute sql: {} exception", buildPageLimit, e);
                }
            }
        }
        if (CollectionUtils.isEmpty(executeResults)) {
            try {
                simpleSqlStatement.setSql(originalSql);
                if (type != null && StringUtils.equalsAnyIgnoreCase(type, ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.MERGE.name(),
                        ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.ANONYMOUS_BLOCK.name())) {
                    if (!originalSql.endsWith(";")) {
                        simpleSqlStatement.setSql(originalSql + ";");
                    }
                }
                executeResults = executeMultiStreaming(simpleSqlStatement, connection, true,
                        offset, count, param.getResultSetId(), consumer, statementListener, cancellation,
                        sqlType, originalSql, pageNo, pageSize, streamResultSequence, statementSequence,
                        executionContext);
                for (ExecuteResponse executeResult : executeResults) {
                    executeResult.setSql(originalSql);
                }
            } catch (Exception ee) {
                ExecuteResponse executeResult = failedExecuteResponse(
                        originalSql, ee, startedAtEpochMs, startedAtNanos, executionContext);
                executeResults.add(executeResult);
            }
        }
        for (ExecuteResponse executeResult : executeResults) {
            executeResult.setStatementSequence(statementSequence);
            executeResult.setSqlType(sqlType.getCode());
            executeResult.setOriginalSql(originalSql);
        }
        consumer.statementFinished(originalSql,
                maximumStatementDuration(executeResults));
        return executeResults;
    }

    protected List<ExecuteResponse> executeMulti(SimpleSqlStatement simpleSqlStatement, Connection connection,
                                             boolean limitRowSize, Integer offset, Integer count,
                                             Integer resultSetId) throws SQLException {
        return executeMulti(simpleSqlStatement, connection, limitRowSize, offset, count, resultSetId,
                JdbcExecutionContext.capture(connection));
    }

    protected List<ExecuteResponse> executeMulti(SimpleSqlStatement simpleSqlStatement, Connection connection,
                                             boolean limitRowSize, Integer offset, Integer count, Integer resultSetId,
                                             ExecutionContext executionContext) throws SQLException {
        String sql = simpleSqlStatement.getSql();
        String type = simpleSqlStatement.getSqlType();
        Assert.notNull(sql, "SQL must not be null");
        ArrayList<ExecuteResponse> executeResults = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            clearWarnings(stmt, connection);
            stmt.setFetchSize(IEasyToolsConstant.MAX_PAGE_SIZE);
            if (sql.toLowerCase().startsWith("select")) {
                if (type == null || !StringUtils.equals(type, ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.SELECT_INTO.name())) {
                    if (offset != null && count != null) {
                        stmt.setMaxRows(offset + count);
                    }
                }
            }
            TimeInterval timeInterval = new TimeInterval();
            long startedAtEpochMs = System.currentTimeMillis();
            long executeStartedNanos = System.nanoTime();
            boolean query = stmt.execute();
            long executeDurationNanos = ExecutionTiming.elapsedNanos(executeStartedNanos);
            executionContext = executionContextAfterExecute(simpleSqlStatement, connection, executionContext);
            int resultCount = 0;
            while (true) {
                ExecuteResponse executeResult = ExecuteResponse.builder().sql(sql).success(Boolean.TRUE).build();
                executeResult.setDescription(I18nUtils.getMessage("sqlResult.success"));
                long fetchDurationNanos = 0L;
                if (query) {
                    resultCount++;
                    if (Objects.isNull(resultSetId) || resultCount == resultSetId) {
                        long fetchStartedNanos = System.nanoTime();
                        executeResult = generateQueryExecuteResponse(stmt, limitRowSize, offset, count);
                        fetchDurationNanos = ExecutionTiming.elapsedNanos(fetchStartedNanos);
                        executeResult.setResultSetId(resultCount);
                    }
                } else {
                    executeResult.setUpdateCount(stmt.getUpdateCount());
                }
                executeResult.setDuration(timeInterval.interval());
                executeResult.setExecutionContext(executionContext);
                executeResult.setExecutionMetrics(ExecutionTiming.complete(
                        ExecutionTiming.started(startedAtEpochMs), executeDurationNanos, fetchDurationNanos,
                        CollectionUtils.size(executeResult.getDataList())));
                executeResults.add(executeResult);
                long nextStartedAtEpochMs = System.currentTimeMillis();
                long nextExecuteStartedNanos = System.nanoTime();
                query = stmt.getMoreResults();
                long nextExecuteDurationNanos = ExecutionTiming.elapsedNanos(nextExecuteStartedNanos);
                if (!query && stmt.getUpdateCount() == -1) {
                    break;
                }
                startedAtEpochMs = nextStartedAtEpochMs;
                executeDurationNanos = nextExecuteDurationNanos;
            }
            attachMessages(executeResults, collectMessages(stmt, connection));
            clearWarnings(stmt, connection);
        }
        return executeResults;
    }

    protected List<ExecuteResponse> executeMultiStreaming(SimpleSqlStatement simpleSqlStatement, Connection connection,
                                                        boolean limitRowSize, Integer offset, Integer count,
                                                        Integer resultSetId, ISqlExecutionResultConsumer consumer,
                                                        ISqlExecutionStatementListener statementListener,
                                                        ISqlExecutionCancellation cancellation, SqlTypeEnum sqlType,
                                                        String originalSql, int pageNo, int pageSize,
                                                        AtomicInteger streamResultSequence,
                                                        int statementSequence) throws SQLException {
        return executeMultiStreaming(simpleSqlStatement, connection, limitRowSize, offset, count, resultSetId,
                consumer, statementListener, cancellation, sqlType, originalSql, pageNo, pageSize,
                streamResultSequence, statementSequence, JdbcExecutionContext.capture(connection));
    }

    protected List<ExecuteResponse> executeMultiStreaming(SimpleSqlStatement simpleSqlStatement, Connection connection,
                                                        boolean limitRowSize, Integer offset, Integer count,
                                                        Integer resultSetId, ISqlExecutionResultConsumer consumer,
                                                        ISqlExecutionStatementListener statementListener,
                                                        ISqlExecutionCancellation cancellation, SqlTypeEnum sqlType,
                                                        String originalSql, int pageNo, int pageSize,
                                                        AtomicInteger streamResultSequence,
                                                        int statementSequence,
                                                        ExecutionContext executionContext) throws SQLException {
        String sql = simpleSqlStatement.getSql();
        String type = simpleSqlStatement.getSqlType();
        Assert.notNull(sql, "SQL must not be null");
        ArrayList<ExecuteResponse> executeResults = new ArrayList<>();
        PreparedStatement stmt = connection.prepareStatement(sql);
        statementListener.onStatementCreated(stmt);
        try (stmt) {
            clearWarnings(stmt, connection);
            stmt.setFetchSize(IEasyToolsConstant.MAX_PAGE_SIZE);
            if (sql.toLowerCase().startsWith("select")) {
                if (type == null || !StringUtils.equals(type, ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.SELECT_INTO.name())) {
                    if (offset != null && count != null) {
                        stmt.setMaxRows(offset + count);
                    }
                }
            }
            TimeInterval timeInterval = new TimeInterval();
            checkCanceled(cancellation);
            long startedAtEpochMs = System.currentTimeMillis();
            long executeStartedNanos = System.nanoTime();
            boolean query = stmt.execute();
            long executeDurationNanos = ExecutionTiming.elapsedNanos(executeStartedNanos);
            executionContext = executionContextAfterExecute(simpleSqlStatement, connection, executionContext);
            int resultCount = 0;
            while (true) {
                checkCanceled(cancellation);
                ExecuteResponse executeResult = ExecuteResponse.builder()
                        .sql(sql)
                        .success(Boolean.TRUE)
                        .statementSequence(statementSequence)
                        .build();
                executeResult.setOriginalSql(originalSql);
                executeResult.setSqlType(sqlType.getCode());
                executeResult.setExecutionContext(executionContext);
                executeResult.setDescription(I18nUtils.getMessage("sqlResult.success"));
                if (query) {
                    resultCount++;
                    if (Objects.isNull(resultSetId) || resultCount == resultSetId) {
                        executeResult = streamQueryExecuteResponse(stmt, limitRowSize, offset, count, consumer,
                                cancellation, sqlType, sql, originalSql, pageNo, pageSize, resultCount,
                                streamResultSequence.incrementAndGet(), statementSequence, startedAtEpochMs,
                                executionContext, executeDurationNanos);
                        executeResult.setSql(sql);
                        executeResult.setOriginalSql(originalSql);
                    }
                } else {
                    executeResult.setUpdateCount(stmt.getUpdateCount());
                    executeResult.setPageNo(pageNo);
                    executeResult.setPageSize(CollectionUtils.size(executeResult.getDataList()));
                    executeResult.setHasNextPage(Boolean.FALSE);
                    executeResult.setFuzzyTotal("0");
                    executeResult.setDuration(timeInterval.interval());
                    executeResult.setExecutionMetrics(ExecutionTiming.complete(
                            ExecutionTiming.started(startedAtEpochMs), executeDurationNanos, 0L,
                            CollectionUtils.size(executeResult.getDataList())));
                    setStreamResultId(executeResult, streamResultSequence.incrementAndGet());
                    consumer.updateCount(executeResult);
                }
                executeResult.setStatementSequence(statementSequence);
                if (executeResult.getExecutionMetrics() == null) {
                    executeResult.setExecutionMetrics(ExecutionTiming.complete(
                            ExecutionTiming.started(startedAtEpochMs), executeDurationNanos, 0L,
                            CollectionUtils.size(executeResult.getDataList())));
                }
                executeResult.setDuration(timeInterval.interval());
                executeResults.add(executeResult);
                long nextStartedAtEpochMs = System.currentTimeMillis();
                long nextExecuteStartedNanos = System.nanoTime();
                query = stmt.getMoreResults();
                long nextExecuteDurationNanos = ExecutionTiming.elapsedNanos(nextExecuteStartedNanos);
                if (!query && stmt.getUpdateCount() == -1) {
                    break;
                }
                startedAtEpochMs = nextStartedAtEpochMs;
                executeDurationNanos = nextExecuteDurationNanos;
            }
            attachMessages(executeResults, collectMessages(stmt, connection));
            clearWarnings(stmt, connection);
        } finally {
            statementListener.onStatementClosed(stmt);
        }
        return executeResults;
    }

    private ExecuteResponse streamQueryExecuteResponse(Statement stmt, boolean limitRowSize, Integer offset, Integer count,
                                                   ISqlExecutionResultConsumer consumer,
                                                   ISqlExecutionCancellation cancellation, SqlTypeEnum sqlType,
                                                   String sql, String originalSql, int pageNo, int pageSize,
                                                   int resultSetId, int streamResultId, int statementSequence,
                                                   long startedAtEpochMs, ExecutionContext executionContext,
                                                   long executeDurationNanos) throws SQLException {
        ExecutionMetrics executionMetrics = ExecutionTiming.started(startedAtEpochMs);
        ExecuteResponse executeResult = ExecuteResponse.builder()
                .success(Boolean.TRUE)
                .statementSequence(statementSequence)
                .executionMetrics(executionMetrics)
                .executionContext(executionContext)
                .build();
        executeResult.setDescription(I18nUtils.getMessage("sqlResult.success"));
        executeResult.setSql(sql);
        executeResult.setOriginalSql(originalSql);
        executeResult.setSqlType(sqlType.getCode());
        executeResult.setResultSetId(resultSetId);
        setStreamResultId(executeResult, streamResultId);
        ResultSet rs = null;
        long fetchDurationNanos = 0L;
        try {
            long metadataStartedNanos = System.nanoTime();
            rs = stmt.getResultSet();
            ResultSetMetaData resultSetMetaData = rs.getMetaData();
            int col = resultSetMetaData.getColumnCount();
            List<Header> headerList = generateHeaderList(resultSetMetaData);
            int chat2dbAutoRowIdIndex = getChat2dbAutoRowIdIndex(headerList);
            fetchDurationNanos = ExecutionTiming.addNanos(
                    fetchDurationNanos, ExecutionTiming.elapsedNanos(metadataStartedNanos));
            executeResult.setHeaderList(headerList);
            executeResult.setDataList(new ArrayList<>());
            SqlUtils.buildCanEditResult(originalSql, JdbcUtils.parse2DruidDbType(Chat2DBContext.getConnectInfo().getDbType()), executeResult);
            addRowNumberHeader(executeResult);
            setPageInfo(executeResult, sqlType, pageNo, pageSize);
            consumer.resultStarted(executeResult);
            StreamingDataResult streamingDataResult = streamDataList(rs, col, chat2dbAutoRowIdIndex, limitRowSize, offset,
                    count, pageNo, pageSize, cancellation, consumer, executeResult);
            fetchDurationNanos = ExecutionTiming.addNanos(
                    fetchDurationNanos, streamingDataResult.fetchDurationNanos());
            executeResult.setDataList(streamingDataResult.dataList());
            setPageInfo(executeResult, sqlType, pageNo, pageSize);
        } finally {
            JdbcUtils.closeResultSet(rs);
        }
        executeResult.setExecutionMetrics(ExecutionTiming.complete(
                executionMetrics, executeDurationNanos, fetchDurationNanos,
                CollectionUtils.size(executeResult.getDataList())));
        return executeResult;
    }

    private void setStreamResultId(ExecuteResponse executeResult, int streamResultId) {
        Map<String, Object> extra = executeResult.getExtra();
        if (extra == null) {
            extra = new HashMap<>();
            executeResult.setExtra(extra);
        }
        extra.put("streamResultId", streamResultId);
    }

    private StreamingDataResult streamDataList(ResultSet rs, int col, int chat2dbAutoRowIdIndex,
                                               boolean limitRowSize, Integer offset, Integer count,
                                               int pageNo, int pageSize,
                                               ISqlExecutionCancellation cancellation,
                                               ISqlExecutionResultConsumer consumer,
                                               ExecuteResponse executeResult) throws SQLException {
        List<List<ResultCell>> dataList = Lists.newArrayList();
        List<List<ResultCell>> batch = Lists.newArrayListWithCapacity(STREAMING_ROW_BATCH_SIZE);
        long startedAtNanos = System.nanoTime();
        long callbackDurationNanos = 0L;
        if (offset == null || offset < 0) {
            offset = 0;
        }
        int rowNumber = 0;
        int rowCount = 1;
        IValueProcessor valueProcessor;
        if (Chat2DBContext.getConnectInfo() != null) {
            valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        } else {
            valueProcessor = new DefaultValueProcessor();
        }
        while (rs.next()) {
            checkCanceled(cancellation);
            if (rowNumber++ < offset) {
                continue;
            }
            List<ResultCell> row = Lists.newArrayListWithExpectedSize(col);
            for (int i = 1; i <= col; i++) {
                if (chat2dbAutoRowIdIndex == i) {
                    continue;
                }
                JDBCDataValue jdbcDataValue = new JDBCDataValue(rs, rs.getMetaData(), i, limitRowSize);
                String value = valueProcessor.getJdbcValue(jdbcDataValue);
                ResultCell cell = jdbcDataValue.buildResultCell(value);
                row.add(cell);
            }
            List<ResultCell> numberedRow = Lists.newArrayListWithExpectedSize(row.size() + 1);
            int rowNumberIncrement = 1 + Math.max(pageNo - 1, 0) * pageSize;
            numberedRow.add(ResultCell.of(Integer.toString(dataList.size() + rowNumberIncrement)));
            numberedRow.addAll(row);
            dataList.add(numberedRow);
            batch.add(numberedRow);
            if (batch.size() >= STREAMING_ROW_BATCH_SIZE) {
                long callbackStartedNanos = System.nanoTime();
                try {
                    consumer.rows(executeResult, new ArrayList<>(batch));
                } finally {
                    callbackDurationNanos = ExecutionTiming.addNanos(
                            callbackDurationNanos, ExecutionTiming.elapsedNanos(callbackStartedNanos));
                }
                batch.clear();
            }
            if (count != null && count > 0 && rowCount++ >= count) {
                break;
            }
        }
        if (!batch.isEmpty()) {
            long callbackStartedNanos = System.nanoTime();
            try {
                consumer.rows(executeResult, new ArrayList<>(batch));
            } finally {
                callbackDurationNanos = ExecutionTiming.addNanos(
                        callbackDurationNanos, ExecutionTiming.elapsedNanos(callbackStartedNanos));
            }
        }
        long fetchDurationNanos = ExecutionTiming.subtractNanos(
                ExecutionTiming.elapsedNanos(startedAtNanos), callbackDurationNanos);
        return new StreamingDataResult(dataList, fetchDurationNanos);
    }

    private record StreamingDataResult(List<List<ResultCell>> dataList, long fetchDurationNanos) {
    }

    private void addRowNumberHeader(ExecuteResponse executeResult) {
        Header rowNumberHeader = Header.builder()
                .name(I18nUtils.getMessage("sqlResult.rowNumber"))
                .dataType(DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode())
                .build();
        executeResult.setHeaderList(EasyCollectionUtils.union(Arrays.asList(rowNumberHeader), executeResult.getHeaderList()));
    }

    private void checkCanceled(ISqlExecutionCancellation cancellation) throws SQLException {
        if (cancellation != null && cancellation.isCanceled()) {
            throw new SQLException("SQL execution canceled");
        }
    }

    private ExecuteResponse failedExecuteResponse(String sql, Exception exception, long startedAtEpochMs,
                                                  long startedAtNanos, ExecutionContext executionContext) {
        ExecutionMetrics executionMetrics = ExecutionTiming.complete(
                ExecutionTiming.started(startedAtEpochMs), ExecutionTiming.elapsedNanos(startedAtNanos), 0L, 0);
        return ExecuteResponse.builder()
                .sql(sql)
                .success(Boolean.FALSE)
                .message(exception.getMessage())
                .duration(executionMetrics.getTotalDurationMs())
                .executionMetrics(executionMetrics)
                .executionContext(executionContext)
                .build();
    }

    protected static long maximumStatementDuration(List<ExecuteResponse> executeResults) {
        return executeResults.stream()
                .map(ExecuteResponse::getDuration)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(0L);
    }

    private void attachMessages(List<ExecuteResponse> executeResults, List<Map<String, Object>> messages) {
        if (CollectionUtils.isEmpty(executeResults) || CollectionUtils.isEmpty(messages)) {
            return;
        }
        ExecuteResponse executeResult = executeResults.get(0);
        Map<String, Object> extra = executeResult.getExtra();
        if (extra == null) {
            extra = new HashMap<>();
            executeResult.setExtra(extra);
        }
        extra.put("messages", messages);
    }

    private List<Map<String, Object>> collectMessages(Statement stmt, Connection connection) throws SQLException {
        List<Map<String, Object>> messages = new ArrayList<>();
        appendWarnings(messages, stmt.getWarnings());
        appendWarnings(messages, connection.getWarnings());
        return messages;
    }

    private void appendWarnings(List<Map<String, Object>> target, SQLWarning warning) throws SQLException {
        SQLWarning current = warning;
        while (current != null) {
            String message = current.getMessage();
            if (StringUtils.isNotBlank(message)) {
                Map<String, Object> item = new HashMap<>();
                item.put("level", "INFO");
                item.put("message", message);
                item.put("errorCode", current.getErrorCode());
                item.put("sqlState", current.getSQLState());
                target.add(item);
            }
            current = current.getNextWarning();
        }
    }

    private void clearWarnings(Statement stmt, Connection connection) {
        try {
            stmt.clearWarnings();
        } catch (SQLException e) {
            log.debug("clear statement warnings error", e);
        }
        try {
            connection.clearWarnings();
        } catch (SQLException e) {
            log.debug("clear connection warnings error", e);
        }
    }


    private SqlTypeEnum getSqlType(DbType dbType, String originalSql) {
        SqlTypeEnum sqlType = SqlTypeEnum.UNKNOWN;
        String type = Chat2DBContext.getConnectInfo().getDbType();
        boolean supportDruid = !DataSourceTypeEnum.MONGODB.getCode().equals(type);
        SQLStatement sqlStatement = null;
        if (supportDruid) {
            try {
                sqlStatement = SQLUtils.parseSingleStatement(originalSql, dbType);
            } catch (Exception e) {
                log.warn("Failed to parse sql: {}", originalSql, e);
            }
        }
        if (!supportDruid || (sqlStatement instanceof SQLSelectStatement)) {
            sqlType = SqlTypeEnum.SELECT;
        }
        return sqlType;
    }

    private void setPageInfo(ExecuteResponse executeResult, SqlTypeEnum sqlType, int pageNo, int pageSize) {
        if (SqlTypeEnum.SELECT.equals(sqlType)) {
            executeResult.setPageNo(pageNo);
            executeResult.setPageSize(pageSize);
            executeResult.setHasNextPage(
                    CollectionUtils.size(executeResult.getDataList()) >= executeResult.getPageSize());
        } else {
            executeResult.setPageNo(pageNo);
            executeResult.setPageSize(CollectionUtils.size(executeResult.getDataList()));
            executeResult.setHasNextPage(Boolean.FALSE);
        }
        executeResult.setFuzzyTotal(calculateFuzzyTotal(pageNo, pageSize, executeResult));
    }


    private void addRowNumber(ExecuteResponse executeResult, int pageNo, int pageSize) {
        List<Header> headers = executeResult.getHeaderList();
        Header rowNumberHeader = Header.builder()
                .name(I18nUtils.getMessage("sqlResult.rowNumber"))
                .dataType(DataTypeEnum.CHAT2DB_ROW_NUMBER
                        .getCode()).build();
        executeResult.setHeaderList(EasyCollectionUtils.union(Arrays.asList(rowNumberHeader), headers));
        if (executeResult.getDataList() != null) {
            int rowNumberIncrement = 1 + Math.max(pageNo - 1, 0) * pageSize;
            for (int i = 0; i < executeResult.getDataList().size(); i++) {
                List<ResultCell> row = executeResult.getDataList().get(i);
                List<ResultCell> newRow = Lists.newArrayListWithExpectedSize(row.size() + 1);
                newRow.add(ResultCell.of(Integer.toString(i + rowNumberIncrement)));
                newRow.addAll(row);
                executeResult.getDataList().set(i, newRow);
            }
        }
    }


    private String calculateFuzzyTotal(int pageNo, int pageSize, ExecuteResponse executeResult) {
        int dataSize = CollectionUtils.size(executeResult.getDataList());
        if (pageSize <= 0) {
            return Integer.toString(dataSize);
        }
        int fuzzyTotal = Math.max(pageNo - 1, 0) * pageSize + dataSize;
        if (dataSize < pageSize) {
            return Integer.toString(fuzzyTotal);
        }
        return fuzzyTotal + "+";
    }

    private ExecuteResponse execute(String sql, Integer offset, Integer count) {
        ExecuteResponse executeResult;
        try {
            executeResult = execute(SqlStatementExecuteRequest.builder()
                    .sql(sql)
                    .connection(Chat2DBContext.getConnection())
                    .limitRowSize(true)
                    .offset(offset)
                    .count(count)
                    .build());
        } catch (SQLException e) {
            log.error("Execute sql: {} exception", sql, e);
            executeResult = ExecuteResponse.builder()
                    .sql(sql)
                    .success(Boolean.FALSE)
                    .message(e.getMessage())
                    .build();
        }
        return executeResult;
    }

    public void execute(Connection connection, String sql, int batchSize, IResultSetConsumer consumer) {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setFetchSize(batchSize);
            boolean query = stmt.execute();
            if (query) {
                try (ResultSet rs = stmt.getResultSet()) {
                    consumer.accept(rs);
                }
            }
        } catch (Exception e) {
            log.error("execute error:{}", sql, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void fetchAllTableRecords(FetchAllTableRecordsRequest request) {
        Connection connection = request.getConnection();
        String sql = request.getSql();
        int batchSize = request.getBatchSize();
        IResultSetConsumer consumer = request.getConsumer();
        try (PreparedStatement stmt = connection.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
        )) {
            connection.setAutoCommit(false);
            stmt.setFetchSize(batchSize);
            ResultSet rs = stmt.executeQuery();
            consumer.accept(rs);
            connection.commit();
            connection.setAutoCommit(true);
        } catch (Exception e) {
            log.error("Failed to fetch table records. Query: {}", sql, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isQueryCommand(Connection connection, String sql) {
        return false;
    }

    public void executeBatchInsert(Connection connection, List<String> sqlCacheList) {
        try {
            for (String sql : sqlCacheList) {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<FunctionParameter> getFunctionParameters(Connection connection, String databaseName, String schemaName, String functionName) {
        try (ResultSet resultSet = connection.getMetaData().getFunctionColumns(databaseName, schemaName, functionName, null)) {
            return ResultSetUtils.toObjectList(resultSet, FunctionParameter.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<ProcedureParameter> getProcedureParameters(Connection connection, String databaseName, String schemaName, String procedureName) {
        try (ResultSet resultSet = connection.getMetaData().getProcedureColumns(databaseName, schemaName, procedureName, null)) {
            return ResultSetUtils.toObjectList(resultSet, ProcedureParameter.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<ForeignKeyInfo> getImportedKeys(Connection connection, String databaseName, String schemaName, String tableName) {
        try (ResultSet resultSet = connection.getMetaData().getImportedKeys(databaseName, schemaName, tableName)) {
            return ResultSetUtils.toObjectList(resultSet, ForeignKeyInfo.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<ForeignKeyInfo> getExportedKeys(Connection connection, String databaseName, String schemaName, String tableName) {
        try (ResultSet resultSet = connection.getMetaData().getExportedKeys(databaseName, schemaName, tableName)) {
            return ResultSetUtils.toObjectList(resultSet, ForeignKeyInfo.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<PrimaryKey> getPrimaryKeys(Connection connection, String databaseName, String schemaName, String tableName) {
        try (ResultSet resultSet = connection.getMetaData().getPrimaryKeys(databaseName, schemaName, tableName)) {
            return ResultSetUtils.toObjectList(resultSet, PrimaryKey.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

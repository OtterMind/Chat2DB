package ai.chat2db.plugin.redis;

import ai.chat2db.plugin.redis.constant.RedisConstants;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.plugin.redis.enums.type.RedisDataType;
import ai.chat2db.plugin.redis.type.ITypeScript;
import ai.chat2db.community.tools.constant.IEasyToolsConstant;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.EasyCollectionUtils;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.ResultSetUtils;
import ai.chat2db.spi.util.SqlUtils;
import cn.hutool.core.date.TimeInterval;
import com.alibaba.druid.DbType;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static ai.chat2db.plugin.redis.enums.type.RedisDataType.*;
import static ai.chat2db.plugin.redis.util.RedisValueUtils.getRedisValue;

@Slf4j
public class RedisScriptExecutor extends DefaultSQLExecutor {

    private static final RedisScriptExecutor INSTANCE = new RedisScriptExecutor();

    public static RedisScriptExecutor getInstance() {
        return INSTANCE;
    }

    public RedisScriptExecutor() {

    }

    public boolean existKey(Connection connection, String key) {
        return DefaultSQLExecutor.getInstance().execute(connection, String.format(RedisConstants.COMMAND_EXISTS_KEY, getRedisValue(key)), resultSet -> {
            if (resultSet.next()) {
                return resultSet.getInt(1) == 1;
            }
            return false;
        });
    }

    @Override
    public List<ExecuteResponse> executeSelectTable(SqlExecuteRequest command) {
        String keyType = getKeyType(command.getTableName());
        ITypeScript typeScript = RedisDataType.fromCode(keyType).getScript();
        RedisKey redisKey = RedisKey.builder()
                .name(command.getTableName())
                .build();
        Map<String, Object> extra = Map.of(RedisConstants.FIELD_KEY_TYPE, keyType, RedisConstants.FIELD_KEY,
                command.getTableName(), RedisConstants.FIELD_TTL, getTtl(command.getTableName()));
        String script = typeScript.getKey(redisKey);
        command.setScript(script);
        List<ExecuteResponse> results = execute(command);
        if (CollectionUtils.isNotEmpty(results)) {
            results.forEach(executeResult -> {
                executeResult.setExtra(extra);
            });
        }
        return results;
    }

    public ExecuteResponse createRedisKey(RedisKey redisKey) {
        ITypeScript typeScript = RedisDataType.fromCode(redisKey.getType()).getScript();
        List<String> script = typeScript.createKey(redisKey);
        ExecuteResponse executeResult = new ExecuteResponse();
        for (String s : script) {
            if (StringUtils.isNotBlank(s)) {
                executeResult = executeUpdate(s);
            }
        }
        return executeResult;
    }

    public String getKeyType(String key) {
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, String.format(RedisConstants.COMMAND_TYPE_KEY, getRedisValue(key)), resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
            return null;
        });
    }

    public String getTtl(String key) {
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, String.format(RedisConstants.COMMAND_TTL_KEY, getRedisValue(key)), resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
            return null;
        });
    }

    public List<ExecuteResponse> execute(SqlExecuteRequest command) {
        String type = Chat2DBContext.getConnectInfo().getDbType();
        DbType dbType = JdbcUtils.parse2DruidDbType(type);

        List<String> sqlList = SqlUtils.parse(command.getScript(), dbType,true);

        if (CollectionUtils.isEmpty(sqlList)) {
            throw new BusinessException(RedisConstants.ERROR_SQL_ANALYSIS);
        }
        List<ExecuteResponse> result = new ArrayList<>();
        for (String originalSql : sqlList) {
            ExecuteResponse executeResult = executeCommand(originalSql);
            result.add(executeResult);
        }
        return result;
    }

    private ExecuteResponse executeCommand(String originalSql) {
        int pageNo = 1;
        int pageSize = 0;
        String sqlType = SqlTypeEnum.UNKNOWN.getCode();
        ExecuteResponse executeResult = null;
        try {
            executeResult = doExecuteCommand(originalSql);
        } catch (SQLException e) {
            throw new IllegalStateException("Redis command execution failed, sql=" + originalSql, e);
        }
        executeResult.setSqlType(sqlType);
        executeResult.setOriginalSql(originalSql);
        executeResult.setPageNo(pageNo);
        pageSize = CollectionUtils.size(executeResult.getDataList());
        executeResult.setPageSize(pageSize);
        executeResult.setHasNextPage(Boolean.FALSE);
        executeResult.setFuzzyTotal(String.valueOf(pageSize));
        appendRowNumber(executeResult, pageNo, pageSize);
        return executeResult;
    }

    private void appendRowNumber(ExecuteResponse executeResult, int pageNo, int pageSize) {
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

    private ExecuteResponse executeUpdate(String sql) {
        ExecuteResponse executeResult = ExecuteResponse.builder().sql(sql).success(Boolean.TRUE).build();
        Connection connection = Chat2DBContext.getConnection();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            TimeInterval timeInterval = new TimeInterval();
            int n = stmt.executeUpdate();
            executeResult.setUpdateCount(n);
            executeResult.setDuration(timeInterval.interval());
        } catch (Exception e) {
            log.error(RedisConstants.LOG_EXECUTE_UPDATE_ERROR, sql, e);
            throw new IllegalStateException("Redis update command failed, sql=" + sql, e);
        }
        return executeResult;
    }

    @Override
    public ExecuteResponse executeUpdate(String sql, Connection connection, int n) {
        ExecuteResponse executeResult = ExecuteResponse.builder().sql(sql).success(Boolean.TRUE).build();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            TimeInterval timeInterval = new TimeInterval();
            int affectedRows = stmt.executeUpdate();
            executeResult.setUpdateCount(affectedRows);
            executeResult.setDuration(timeInterval.interval());
        } catch (Exception e) {
            log.error(RedisConstants.LOG_EXECUTE_UPDATE_ERROR, sql, e);
            throw new IllegalStateException("Redis update command failed, sql=" + sql, e);
        }
        return executeResult;
    }


    private ExecuteResponse doExecuteCommand(String sql)
            throws SQLException {
        Assert.notNull(sql, RedisConstants.ERROR_SQL_MUST_NOT_BE_NULL);
        log.info(RedisConstants.LOG_EXECUTE, sql);
        Connection connection = Chat2DBContext.getConnection();
        ExecuteResponse executeResult = ExecuteResponse.builder().sql(sql).success(Boolean.TRUE).build();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setFetchSize(IEasyToolsConstant.MAX_PAGE_SIZE);
            TimeInterval timeInterval = new TimeInterval();
            boolean query = stmt.execute();
            executeResult.setDescription(I18nUtils.getMessage("sqlResult.success"));
            if (query) {
                executeResult = buildQueryCommandResult(stmt, sql);
            } else {
                executeResult.setDuration(timeInterval.interval());
                executeResult.setUpdateCount(stmt.getUpdateCount());
            }
            executeResult.setDuration(timeInterval.interval());
        }
        return executeResult;
    }

    private ExecuteResponse buildQueryCommandResult(Statement stmt, String sql) throws SQLException {
        ExecuteResponse executeResult = ExecuteResponse.builder().sql(sql).success(Boolean.TRUE).build();
        ResultSet rs = null;
        try {
            rs = stmt.getResultSet();
            ResultSetMetaData resultSetMetaData = rs.getMetaData();
            int col = resultSetMetaData.getColumnCount();
            List<Header> headerList = getHeaderList(resultSetMetaData);
            executeResult.setHeaderList(headerList);
            List<List<ResultCell>> dataList = getDataList(rs, col);
            executeResult.setDataList(dataList);
        } finally {
            JdbcUtils.closeResultSet(rs);
        }
        return executeResult;
    }

    private List<Header> getHeaderList(ResultSetMetaData resultSetMetaData) throws SQLException {
        int col = resultSetMetaData.getColumnCount();
        List<Header> headerList = Lists.newArrayListWithExpectedSize(col);
        for (int i = 1; i <= col; i++) {
            String name = ResultSetUtils.getColumnName(resultSetMetaData, i);
            String dataType = JdbcUtils.resolveDataType(
                    resultSetMetaData.getColumnTypeName(i), resultSetMetaData.getColumnType(i)).getCode();
            headerList.add(Header.builder()
                    .dataType(dataType)
                    .name(name)
                    .build());
        }
        return headerList;
    }

    private List<List<ResultCell>> getDataList(ResultSet rs, int col) throws SQLException {
        List<List<ResultCell>> dataList = Lists.newArrayList();
        while (rs.next()) {
            List<ResultCell> row = Lists.newArrayListWithExpectedSize(col);
            dataList.add(row);
            for (int i = 1; i <= col; i++) {
                row.add(ResultCell.of(rs.getString(i)));
            }

        }
        return dataList;
    }

    public RedisKey getKey(String key) {
        String keyType = getKeyType(key);
        Connection connection = Chat2DBContext.getConnection();
        ITypeScript typeScript = RedisDataType.fromCode(keyType).getScript();
        RedisKey redisKey = RedisKey.builder()
                .name(key)
                .type(keyType)
                .build();
        return typeScript.getKeyR(connection, redisKey);
    }


    public ExecuteResponse update(RedisKey oldKey, RedisKey newKey) {
        if (oldKey == null && newKey == null) {
            return new ExecuteResponse();
        }
        List<String> scripts = new ArrayList<>();
        boolean typeChanged = oldKey != null && newKey != null && !oldKey.getType().equals(newKey.getType());
        if (typeChanged) {
            List<String> addScript = RedisDataType.fromCode(newKey.getType()).getScript().createKey(newKey);
            if (CollectionUtils.isEmpty(addScript)) {
                // Nothing to write for the new type; abort instead of deleting the old key.
                return new ExecuteResponse();
            }
            ITypeScript typeScript = RedisDataType.fromCode(oldKey.getType()).getScript();
            List<String> script = typeScript.updateKey(oldKey, null);
            if (CollectionUtils.isNotEmpty(script)) {
                scripts.addAll(script);
            }
            scripts.addAll(addScript);
        } else {
            if (oldKey != null && newKey != null && !oldKey.getName().equals(newKey.getName())) {
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(RedisConstants.SQL_RENAME_KEY_PREFIX).append(getRedisValue(oldKey.getName()))
                        .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR).append(getRedisValue(newKey.getName()))
                        .append(RedisConstants.COMMAND_LINE_SEPARATOR);
                scripts.add(stringBuilder.toString());
            }
            RedisKey typeSource = oldKey != null ? oldKey : newKey;
            ITypeScript typeScript = RedisDataType.fromCode(typeSource.getType()).getScript();
            List<String> script = typeScript.updateKey(oldKey, newKey);
            if (CollectionUtils.isNotEmpty(script)) {
                scripts.addAll(script);
            }
        }
        if (newKey != null && newKey.getTtl() != null && newKey.getTtl() > 0) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(RedisConstants.COMMAND_EXPIRE_KEY_PREFIX).append(getRedisValue(newKey.getName()))
                    .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR).append(newKey.getTtl());
            scripts.add(stringBuilder.toString());
        }
        ExecuteResponse executeResult = new ExecuteResponse();
        for (String s : scripts) {
            if (StringUtils.isNotBlank(s)) {
                executeResult = executeUpdate(s);
            }
        }
        return executeResult;
    }

}

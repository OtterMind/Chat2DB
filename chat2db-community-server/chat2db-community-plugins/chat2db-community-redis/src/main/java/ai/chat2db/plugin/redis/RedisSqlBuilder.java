package ai.chat2db.plugin.redis;

import ai.chat2db.plugin.redis.constant.RedisConstants;
import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.plugin.redis.enums.type.RedisDataType;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.sql.builder.IDatabaseSqlBuilder;
import ai.chat2db.spi.sql.builder.IDdlSqlBuilder;
import ai.chat2db.spi.sql.builder.IDmlSqlBuilder;
import ai.chat2db.spi.sql.builder.IDqlSqlBuilder;
import ai.chat2db.spi.sql.builder.ISchemaSqlBuilder;
import ai.chat2db.spi.sql.builder.ITableSqlBuilder;
import ai.chat2db.spi.sql.builder.ITablespaceSqlBuilder;
import ai.chat2db.spi.sql.builder.IViewSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.model.request.DeleteSqlRequest;
import ai.chat2db.spi.model.request.MultiInsertSqlRequest;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.model.async.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.TruncateTableRequest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static ai.chat2db.plugin.redis.util.RedisValueUtils.getRedisValue;

public class RedisSqlBuilder implements ISqlBuilder, IDqlSqlBuilder, IDmlSqlBuilder, IDdlSqlBuilder,
        IDatabaseSqlBuilder, ISchemaSqlBuilder, ITableSqlBuilder, IViewSqlBuilder, ITablespaceSqlBuilder {

    private static final RedisSqlBuilder INSTANCE = new RedisSqlBuilder();

    public static RedisSqlBuilder getInstance() {
        return INSTANCE;
    }

    public RedisSqlBuilder() {

    }

    @Override
    public IDqlSqlBuilder dql() {
        return this;
    }

    @Override
    public IDmlSqlBuilder dml() {
        return this;
    }

    @Override
    public IDdlSqlBuilder ddl() {
        return this;
    }

    @Override
    public IDatabaseSqlBuilder database() {
        return this;
    }

    @Override
    public ISchemaSqlBuilder schema() {
        return this;
    }

    @Override
    public ITableSqlBuilder table() {
        return this;
    }

    @Override
    public IViewSqlBuilder view() {
        return this;
    }

    @Override
    public ITablespaceSqlBuilder tablespace() {
        return this;
    }

    @Override
    public String buildCreateTablespace(Tablespace tablespace) {
        throw unsupported(RedisConstants.METHOD_BUILD_CREATE_TABLESPACE);
    }

    @Override
    public String buildDropTablespace(String tablespaceName) {
        throw unsupported(RedisConstants.METHOD_BUILD_DROP_TABLESPACE);
    }

    @Override
    public String buildRenameTablespace(String oldTablespaceName, String newTablespaceName) {
        throw unsupported(RedisConstants.METHOD_BUILD_ALTER_TABLESPACE_RENAME);
    }

    @Override
    public String buildAlterTablespaceAddDatafile(String tablespaceName, String dataFile) {
        throw unsupported(RedisConstants.METHOD_BUILD_ALTER_TABLESPACE_ADD_DATAFILE);
    }

    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        throw unsupported(RedisConstants.METHOD_BUILD_CREATE_TABLE);
    }

    public String buildCreateKeySql(RedisKey redisKey) {
        List<String> scripts = RedisDataType.fromCode(redisKey.getType()).getScript().createKey(redisKey);
        if (CollectionUtils.isEmpty(scripts)) {
            return StringUtils.EMPTY;
        }
        return StringUtils.join(scripts, SQLConstants.SEMICOLON_LINE_SEPARATOR);
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        throw unsupported(RedisConstants.METHOD_BUILD_ALTER_TABLE);
    }

    @Override
    public String buildDropTable(DropTableRequest request) {
        throw unsupported(RedisConstants.METHOD_BUILD_DROP_TABLE);
    }

    @Override
    public String buildTruncateTable(TruncateTableRequest request) {
        throw unsupported(RedisConstants.METHOD_BUILD_TRUNCATE_TABLE);
    }

    @Override
    public String buildPageLimit(PageLimitRequest request) {
        throw unsupported(RedisConstants.METHOD_BUILD_PAGE_LIMIT);
    }

    @Override
    public String buildCreateDatabase(Database database) {
        throw unsupported(RedisConstants.METHOD_BUILD_CREATE_DATABASE);
    }

    @Override
    public String buildAlterDatabase(Database oldDatabase, Database newDatabase) {
        throw unsupported(RedisConstants.METHOD_BUILD_ALTER_DATABASE);
    }

    @Override
    public String buildDropDatabase(String databaseName) {
        throw unsupported(RedisConstants.METHOD_BUILD_DROP_DATABASE);
    }

    @Override
    public String buildUseDatabase(String databaseName) {
        throw unsupported(RedisConstants.METHOD_BUILD_USE_DATABASE);
    }

    @Override
    public String buildCreateSchema(Schema schemaName) {
        throw unsupported(RedisConstants.METHOD_BUILD_CREATE_SCHEMA);
    }

    @Override
    public String buildAlterSchema(String oldSchemaName, String newSchemaName) {
        throw unsupported(RedisConstants.METHOD_BUILD_ALTER_SCHEMA);
    }

    @Override
    public String buildDropSchema(String schemaName) {
        throw unsupported(RedisConstants.METHOD_BUILD_DROP_SCHEMA);
    }

    @Override
    public String buildOrderBy(String originSql, List<OrderBy> orderByList) {
        throw unsupported(RedisConstants.METHOD_BUILD_ORDER_BY);
    }

    @Override
    public String buildByQueryResult(QueryResponse queryResult) {
        List<Header> headerList = queryResult.getHeaderList();
        List<ResultOperation> operations = queryResult.getOperations();
        String tableName = queryResult.getTableName();
        if (StringUtils.isBlank(tableName) || CollectionUtils.isEmpty(operations)) {
            return StringUtils.EMPTY;
        }
        String redisKeyType = RedisScriptExecutor.getInstance().getKeyType(tableName);
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(RedisConstants.REDIS_MULTI_COMMAND);
        stringBuilder.append(renameOrUpdateTtl(queryResult));
        for (ResultOperation operation : operations) {
            stringBuilder.append(buildOperationScript(redisKeyType, queryResult.getTableName(), headerList, operation));
        }
        stringBuilder.append(RedisConstants.REDIS_EXEC_COMMAND);
        return stringBuilder.toString();
    }

    private String buildOperationScript(String redisKeyType, String keyName, List<Header> headerList,
            ResultOperation operation) {
        Map<String, String> oldRow = rowByHeader(headerList, operation.getOldDataList());
        Map<String, String> newRow = rowByHeader(headerList, operation.getDataList());
        String operationType = operation.getType();
        StringBuilder script = new StringBuilder();
        switch (RedisDataType.fromCode(redisKeyType)) {
            case HASH:
                appendHashOperation(script, keyName, operationType, oldRow, newRow);
                break;
            case SET:
                appendSetOperation(script, keyName, operationType, oldRow, newRow);
                break;
            case ZSET:
                appendZSetOperation(script, keyName, operationType, oldRow, newRow);
                break;
            case LIST:
                appendListOperation(script, keyName, operationType, operation, oldRow, newRow);
                break;
            case STRING:
                appendStringOperation(script, keyName, operationType, newRow);
                break;
            default:
                break;
        }
        return script.toString();
    }

    private Map<String, String> rowByHeader(List<Header> headerList, List<String> dataList) {
        Map<String, String> row = new HashMap<>();
        if (CollectionUtils.isEmpty(headerList) || CollectionUtils.isEmpty(dataList)) {
            return row;
        }
        for (int i = 0; i < headerList.size() && i < dataList.size(); i++) {
            Header header = headerList.get(i);
            if (header != null && StringUtils.isNotBlank(header.getName())) {
                row.put(header.getName(), dataList.get(i));
            }
        }
        return row;
    }

    private void appendHashOperation(StringBuilder script, String keyName, String operationType,
            Map<String, String> oldRow, Map<String, String> newRow) {
        String oldField = oldRow.get(RedisConstants.FIELD_FIELD);
        String newField = newRow.get(RedisConstants.FIELD_FIELD);
        if (SQLConstants.CREATE_KEYWORD.equals(operationType)) {
            if (StringUtils.isNotBlank(newField)) {
                appendCommand(script, RedisConstants.COMMAND_HASH_SET_PREFIX, keyName, newField,
                        newRow.get(RedisConstants.FIELD_VALUE));
            }
        } else if (SQLConstants.UPDATE_KEYWORD.equals(operationType)) {
            if (StringUtils.isNotBlank(oldField) && !Objects.equals(oldField, newField)) {
                appendCommand(script, RedisConstants.COMMAND_HASH_DELETE_PREFIX, keyName, oldField);
            }
            if (StringUtils.isNotBlank(newField)) {
                appendCommand(script, RedisConstants.COMMAND_HASH_SET_PREFIX, keyName, newField,
                        newRow.get(RedisConstants.FIELD_VALUE));
            }
        } else if (SQLConstants.DELETE_KEYWORD.equals(operationType)) {
            if (StringUtils.isNotBlank(oldField)) {
                appendCommand(script, RedisConstants.COMMAND_HASH_DELETE_PREFIX, keyName, oldField);
            }
        }
    }

    private void appendSetOperation(StringBuilder script, String keyName, String operationType,
            Map<String, String> oldRow, Map<String, String> newRow) {
        String oldValue = oldRow.get(RedisConstants.FIELD_VALUE);
        String newValue = newRow.get(RedisConstants.FIELD_VALUE);
        if (SQLConstants.CREATE_KEYWORD.equals(operationType)) {
            appendCommand(script, RedisConstants.COMMAND_SET_ADD_PREFIX, keyName, newValue);
        } else if (SQLConstants.UPDATE_KEYWORD.equals(operationType)) {
            if (!Objects.equals(oldValue, newValue)) {
                appendCommand(script, RedisConstants.COMMAND_SET_REMOVE_PREFIX, keyName, oldValue);
                appendCommand(script, RedisConstants.COMMAND_SET_ADD_PREFIX, keyName, newValue);
            }
        } else if (SQLConstants.DELETE_KEYWORD.equals(operationType)) {
            appendCommand(script, RedisConstants.COMMAND_SET_REMOVE_PREFIX, keyName, oldValue);
        }
    }

    private void appendZSetOperation(StringBuilder script, String keyName, String operationType,
            Map<String, String> oldRow, Map<String, String> newRow) {
        String oldValue = oldRow.get(RedisConstants.FIELD_VALUE);
        String newValue = newRow.get(RedisConstants.FIELD_VALUE);
        String newScore = StringUtils.defaultIfBlank(newRow.get(RedisConstants.FIELD_SCORE), "0");
        if (SQLConstants.CREATE_KEYWORD.equals(operationType)) {
            appendZAddCommand(script, keyName, newScore, newValue);
        } else if (SQLConstants.UPDATE_KEYWORD.equals(operationType)) {
            if (!Objects.equals(oldValue, newValue)) {
                appendCommand(script, RedisConstants.COMMAND_ZSET_REMOVE_PREFIX, keyName, oldValue);
                appendZAddCommand(script, keyName, newScore, newValue);
            } else if (!Objects.equals(oldRow.get(RedisConstants.FIELD_SCORE), newRow.get(RedisConstants.FIELD_SCORE))) {
                appendZAddCommand(script, keyName, newScore, newValue);
            }
        } else if (SQLConstants.DELETE_KEYWORD.equals(operationType)) {
            appendCommand(script, RedisConstants.COMMAND_ZSET_REMOVE_PREFIX, keyName, oldValue);
        }
    }

    private void appendListOperation(StringBuilder script, String keyName, String operationType,
            ResultOperation operation, Map<String, String> oldRow, Map<String, String> newRow) {
        if (SQLConstants.CREATE_KEYWORD.equals(operationType)) {
            appendCommand(script, RedisConstants.COMMAND_LIST_RIGHT_PUSH_PREFIX, keyName,
                    newRow.get(RedisConstants.FIELD_VALUE));
        } else if (SQLConstants.UPDATE_KEYWORD.equals(operationType)) {
            Integer index = rowNumberIndex(operation.getOldDataList());
            if (index != null) {
                script.append("LSET ").append(getRedisValue(keyName))
                        .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR).append(index)
                        .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR)
                        .append(getRedisValue(StringUtils.defaultString(newRow.get(RedisConstants.FIELD_VALUE))))
                        .append(SQLConstants.LINE_SEPARATOR);
            }
        } else if (SQLConstants.DELETE_KEYWORD.equals(operationType)) {
            Integer index = rowNumberIndex(operation.getOldDataList());
            if (index != null) {
                // Positional delete so a later duplicate occurrence removes exactly the
                // edited row: LSET idx <tombstone> then LREM 1 <tombstone>. Value-based
                // LREM would always hit the FIRST duplicate instead.
                String tombstone = "__chat2db_deleted__" + UUID.randomUUID();
                script.append("LSET ").append(getRedisValue(keyName))
                        .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR).append(index)
                        .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR).append(getRedisValue(tombstone))
                        .append(SQLConstants.LINE_SEPARATOR);
                script.append(RedisConstants.COMMAND_LIST_REMOVE_PREFIX).append(getRedisValue(keyName))
                        .append(RedisConstants.COMMAND_LIST_REMOVE_ONE_FRAGMENT)
                        .append(getRedisValue(tombstone))
                        .append(SQLConstants.LINE_SEPARATOR);
            } else {
                script.append(RedisConstants.COMMAND_LIST_REMOVE_PREFIX).append(getRedisValue(keyName))
                        .append(RedisConstants.COMMAND_LIST_REMOVE_ONE_FRAGMENT)
                        .append(getRedisValue(StringUtils.defaultString(oldRow.get(RedisConstants.FIELD_VALUE))))
                        .append(SQLConstants.LINE_SEPARATOR);
            }
        }
    }

    private Integer rowNumberIndex(List<String> oldDataList) {
        if (CollectionUtils.isEmpty(oldDataList)) {
            return null;
        }
        try {
            return Integer.parseInt(oldDataList.get(0).trim()) - 1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void appendStringOperation(StringBuilder script, String keyName, String operationType,
            Map<String, String> newRow) {
        if (SQLConstants.CREATE_KEYWORD.equals(operationType) || SQLConstants.UPDATE_KEYWORD.equals(operationType)) {
            appendCommand(script, RedisConstants.COMMAND_SET_KEY_PREFIX, keyName,
                    newRow.get(RedisConstants.FIELD_VALUE));
        } else if (SQLConstants.DELETE_KEYWORD.equals(operationType)) {
            script.append(RedisConstants.COMMAND_DELETE_KEY_PREFIX).append(getRedisValue(keyName))
                    .append(SQLConstants.LINE_SEPARATOR);
        }
    }

    private void appendZAddCommand(StringBuilder script, String keyName, String score, String value) {
        script.append(RedisConstants.COMMAND_ZSET_ADD_PREFIX).append(getRedisValue(keyName))
                .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR)
                .append(getRedisValue(StringUtils.defaultString(score)))
                .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR)
                .append(getRedisValue(StringUtils.defaultString(value)))
                .append(SQLConstants.LINE_SEPARATOR);
    }

    private void appendCommand(StringBuilder script, String commandPrefix, String keyName, String... args) {
        script.append(commandPrefix).append(getRedisValue(keyName));
        for (String arg : args) {
            script.append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR)
                    .append(getRedisValue(StringUtils.defaultString(arg)));
        }
        script.append(SQLConstants.LINE_SEPARATOR);
    }

    private String renameOrUpdateTtl(QueryResponse queryResult) {
        if (MapUtils.isEmpty(queryResult.getExtra())) {
            return StringUtils.EMPTY;
        }
        Map<String, Object> extra = queryResult.getExtra();
        StringBuilder stringBuilder = new StringBuilder();
        String key = MapUtils.getString(extra, RedisConstants.FIELD_KEY);
        String ttl = MapUtils.getString(extra, RedisConstants.FIELD_TTL);
        if (StringUtils.isNotBlank(key) && !StringUtils.equals(key, queryResult.getTableName())) {
            stringBuilder.append(RedisConstants.SQL_RENAME_KEY_PREFIX).append(getRedisValue(queryResult.getTableName()))
                    .append(SQLConstants.SPACE).append(getRedisValue(key)).append(SQLConstants.LINE_SEPARATOR);
            queryResult.setTableName(key);
        }
        if (isPositiveTtl(ttl)) {
            stringBuilder.append(RedisConstants.COMMAND_EXPIRE_KEY_PREFIX).append(getRedisValue(queryResult.getTableName()))
                    .append(SQLConstants.SPACE).append(ttl.trim()).append(SQLConstants.LINE_SEPARATOR);
        }
        return stringBuilder.toString();
    }

    private boolean isPositiveTtl(String ttl) {
        if (StringUtils.isBlank(ttl)) {
            return false;
        }
        try {
            return Long.parseLong(ttl.trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }


    @Override
    public String buildTemplate(Table table, String type) {
        if (table == null) {
            return SQLConstants.EMPTY;
        }
        return SQLConstants.EMPTY;
    }

    @Override
    public String buildSelectTable(String databaseName, String schemaName, String tableName) {
        throw unsupported(RedisConstants.METHOD_BUILD_SELECT_TABLE);
    }

    @Override
    public String buildSelectCount(String databaseName, String schemaName, String tableName) {
        throw unsupported(RedisConstants.METHOD_BUILD_SELECT_COUNT);
    }

    @Override
    public String buildInsert(SingleInsertSqlRequest request) {
        throw unsupported(RedisConstants.METHOD_BUILD_INSERT);
    }

    @Override
    public String buildBatchInsert(MultiInsertSqlRequest request) {
        throw unsupported(RedisConstants.METHOD_BUILD_BATCH_INSERT);
    }

    @Override
    public String buildUpdate(UpdateSqlRequest request) {
        throw unsupported(RedisConstants.METHOD_BUILD_UPDATE);
    }

    @Override
    public String buildDelete(DeleteSqlRequest deleteSqlRequest) {
        throw unsupported(RedisConstants.METHOD_BUILD_DELETE);
    }

    @Override
    public String buildCopyByQueryResult(QueryResponse queryResult) {
        return SQLConstants.EMPTY;
    }

    @Override
    public String buildCreateView(ModifyView modifyView) {
        throw unsupported(RedisConstants.METHOD_BUILD_CREATE_VIEW);
    }

    @Override
    public String buildAlterView(ModifyView modifyView) {
        throw unsupported(RedisConstants.METHOD_BUILD_ALTER_VIEW);
    }

    @Override
    public String buildDropView(String databaseName, String schemaName, String viewName) {
        throw unsupported(RedisConstants.METHOD_BUILD_DROP_VIEW);
    }

    @Override
    public String buildShowCreateView(String databaseName, String schemaName, String viewName) {
        throw unsupported(RedisConstants.METHOD_BUILD_SHOW_CREATE_VIEW);
    }

    @Override
    public String buildExplain(String sql) {
        throw unsupported(RedisConstants.METHOD_BUILD_EXPLAIN);
    }

    @Override
    public String buildAITableSchema(Table table) {
        throw unsupported(RedisConstants.METHOD_BUILD_AI_TABLE_SCHEMA);
    }

    private UnsupportedOperationException unsupported(String methodName) {
        return new UnsupportedOperationException(RedisConstants.ERROR_UNSUPPORTED_SQL_BUILDER_METHOD_PREFIX + methodName);
    }
}

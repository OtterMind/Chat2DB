package ai.chat2db.plugin.redis;

import ai.chat2db.plugin.redis.config.RedisScanConfig;
import ai.chat2db.plugin.redis.constant.RedisCommandTemplates;
import ai.chat2db.plugin.redis.constant.RedisConstants;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.plugin.redis.model.RedisKeyScanResult;
import ai.chat2db.plugin.redis.enums.type.RedisDataType;
import ai.chat2db.plugin.redis.type.ITypeScript;
import ai.chat2db.plugin.redis.util.RedisScanUtils;
import ai.chat2db.plugin.redis.util.RedisUrlUtils;
import ai.chat2db.plugin.redis.util.RedisValueUtils;
import ai.chat2db.community.tools.wrapper.result.PageResult;
import ai.chat2db.spi.ICommandExecutor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IKeyOperations;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
public class RedisMetaData extends DefaultMetaService implements IDbMetaData {

    private final IKeyOperations keyOperations = new RedisKeyOperations(this);

    @Override
    public IKeyOperations keyOperations() {
        return keyOperations;
    }

    @Override
    public List<Database> databases(Connection connection) {
        try {
            return getDatabasesByConfig(connection);
        } catch (RuntimeException e) {
            if (isConfigDatabasesUnavailable(e)) {
                log.warn("Redis command {} is unavailable, fallback to {} database probing",
                    RedisConstants.COMMAND_CONFIG_GET_DATABASES,
                    RedisConstants.COMMAND_SELECT_DATABASE_PREFIX.trim(), e);
                return probeDatabases(connection);
            }
            throw e;
        } catch (SQLException e) {
            if (isConfigDatabasesUnavailable(e)) {
                log.warn("Redis command {} is unavailable, fallback to {} database probing",
                    RedisConstants.COMMAND_CONFIG_GET_DATABASES,
                    RedisConstants.COMMAND_SELECT_DATABASE_PREFIX.trim(), e);
                return probeDatabases(connection);
            }
            throw new RuntimeException(e);
        }
    }

    private List<Database> getDatabasesByConfig(Connection connection) throws SQLException {
        List<Database> databases = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(RedisConstants.COMMAND_CONFIG_GET_DATABASES)) {
            boolean query = statement.execute();
            if (query) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    if (resultSet.next()) {
                        Object count = resultSet.getObject(RedisConstants.CONFIG_DATABASES_VALUE_INDEX);
                        String countValue = count == null ? null : count.toString();
                        if (StringUtils.isNotBlank(countValue)) {
                            for (int i = 0; i < Integer.parseInt(countValue); i++) {
                                databases.add(Database.builder().name(String.valueOf(i)).build());
                            }
                        }
                    }
                }
            }
            return databases;
        }
    }

    private boolean isConfigDatabasesUnavailable(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (StringUtils.containsIgnoreCase(message, RedisConstants.CONFIG_GET_PERMISSION_ERROR)
                || StringUtils.containsIgnoreCase(message, RedisConstants.CONFIG_PERMISSION_ERROR)
                || (StringUtils.containsIgnoreCase(message, RedisConstants.UNKNOWN_COMMAND_ERROR)
                    && StringUtils.containsIgnoreCase(message, RedisConstants.COMMAND_CONFIG))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private List<Database> probeDatabases(Connection connection) {
        List<Database> databases = new ArrayList<>();
        String originalDatabase = getOriginalDatabase(connection);
        log.info("Start probing Redis databases by {}, original database: {}, max probe count: {}",
            RedisConstants.COMMAND_SELECT_DATABASE_PREFIX.trim(), originalDatabase,
            RedisConstants.MAX_DATABASE_PROBE_COUNT);
        try {
            for (int i = 0; i < RedisConstants.MAX_DATABASE_PROBE_COUNT; i++) {
                try {
                    executeCommand(connection, RedisConstants.COMMAND_SELECT_DATABASE_PREFIX + i);
                    databases.add(Database.builder().name(String.valueOf(i)).build());
                } catch (RuntimeException e) {
                    if (isSelectDatabaseOutOfRange(e)) {
                        log.info("Redis database probing stopped at index {}, detected {} databases", i,
                            databases.size());
                        break;
                    }
                    log.warn("Redis database probing failed at index {}, detected {} databases, fallback to detected"
                            + " databases or current database {}",
                        i, databases.size(), originalDatabase, e);
                    break;
                } catch (SQLException e) {
                    if (isSelectDatabaseOutOfRange(e)) {
                        log.info("Redis database probing stopped at index {}, detected {} databases", i,
                            databases.size());
                        break;
                    }
                    log.warn("Redis database probing failed at index {}, detected {} databases, fallback to detected"
                            + " databases or current database {}",
                        i, databases.size(), originalDatabase, e);
                    break;
                }
            }
        } finally {
            selectDatabase(connection, originalDatabase);
        }
        if (databases.isEmpty()) {
            log.warn("Redis database probing produced no result, fallback to current database {}", originalDatabase);
            databases.add(Database.builder().name(originalDatabase).build());
        } else {
            log.info("Redis database probing completed, detected {} databases", databases.size());
        }
        if (databases.size() == RedisConstants.MAX_DATABASE_PROBE_COUNT) {
            log.warn("Redis database probing reached max probe count {}, detected databases may be truncated",
                RedisConstants.MAX_DATABASE_PROBE_COUNT);
        }
        return databases;
    }

    private boolean isSelectDatabaseOutOfRange(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (StringUtils.containsIgnoreCase(message, RedisConstants.SELECT_DATABASE_OUT_OF_RANGE_ERROR)
                || (StringUtils.containsIgnoreCase(message, RedisConstants.SELECT_COMMAND_ERROR)
                    && StringUtils.containsIgnoreCase(message, RedisConstants.OUT_OF_RANGE_ERROR))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private void selectDatabase(Connection connection, String database) {
        try {
            executeCommand(connection, RedisConstants.COMMAND_SELECT_DATABASE_PREFIX + database);
        } catch (RuntimeException ignored) {
            log.warn("Redis failed to restore original database {}", database, ignored);
        } catch (SQLException ignored) {
            log.warn("Redis failed to restore original database {}", database, ignored);
        }
    }

    private void executeCommand(Connection connection, String command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(command)) {
            statement.execute();
        }
    }

    private String getOriginalDatabase(Connection connection) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo != null) {
            if (StringUtils.isNotBlank(connectInfo.getDatabaseName())) {
                return connectInfo.getDatabaseName();
            }
            String database = RedisUrlUtils.getDatabaseFromUrl(connectInfo.getUrl());
            if (StringUtils.isNotBlank(database)) {
                return database;
            }
        }
        try {
            String catalog = connection.getCatalog();
            return StringUtils.defaultIfBlank(catalog, RedisConstants.DEFAULT_DATABASE);
        } catch (SQLException e) {
            return RedisConstants.DEFAULT_DATABASE;
        }
    }

    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        String query = String.format(RedisCommandTemplates.SCAN_MATCH_COUNT, RedisConstants.SCAN_INITIAL_CURSOR,
                RedisValueUtils.getRedisValue("*"), RedisScanConfig.DEFAULT.tableScanCount());
        return DefaultSQLExecutor.getInstance().execute(connection, query, resultSet -> {
            List<Table> tables = new ArrayList<>();
            while (resultSet.next()) {
                List<?> keys = RedisScanUtils.getKeys(resultSet.getObject(2));
                for (Object object : keys) {
                    Table table = new Table();
                    table.setName(object.toString());
                    tables.add(table);
                }
            }
            return tables;
        });
    }

    public List<RedisKey> keys(Connection connection, String databaseName, String schemaName, String tableName) {
        if (StringUtils.isBlank(tableName)) {
            return findTop(connection);
        } else {
            List<RedisKey> redisKeys = new ArrayList<>();
            boolean exit = RedisScriptExecutor.getInstance().existKey(connection, tableName);
            if (exit) {
                RedisKey redisKey = findKey(connection, tableName);
                if (redisKey != null) {
                    redisKeys.add(redisKey);
                }
                return redisKeys;
            } else {
                match(connection, redisKeys, tableName, RedisConstants.SCAN_INITIAL_CURSOR);
                return redisKeys;
            }
        }
    }

    public RedisKeyScanResult scanKeys(Connection connection, String searchKey, String cursor, Integer count) {
        return RedisKeyScanner.getInstance().scanKeys(connection, searchKey, cursor, count);
    }

    public RedisKey keyDetail(String keyName) {
        RedisKey redisKey = RedisScriptExecutor.getInstance().getKey(keyName);
        if (redisKey != null) {
            return redisKey;
        }
        RedisKey noneKey = new RedisKey();
        noneKey.setName(keyName);
        noneKey.setType(RedisDataType.NONE.getCode());
        noneKey.setTtl(-2L);
        return noneKey;
    }

    private RedisKey findKey(Connection connection, String key) {
        RedisKey redisKey = new RedisKey();
        redisKey.setName(key);
        String keyType = RedisScriptExecutor.getInstance().getKeyType(key);
        redisKey.setType(keyType);
        ITypeScript typeScript = RedisDataType.fromCode(keyType).getScript();
        return typeScript.getKeyR(connection, redisKey);
    }

    private void match(Connection connection, List<RedisKey> redisKeys, String pattern, String cursor) {
        String query = String.format(RedisCommandTemplates.SCAN_MATCH_COUNT, cursor,
                RedisValueUtils.getRedisValue(RedisScanUtils.buildContainsMatchPattern(pattern)),
                RedisScanConfig.DEFAULT.legacyMatchCount());
        cursor = DefaultSQLExecutor.getInstance().execute(connection, query, resultSet -> {
            String nextCursor = RedisConstants.SCAN_INITIAL_CURSOR;
            while (resultSet.next()) {
                Object cou = resultSet.getObject(1);
                if (cou != null) {
                    nextCursor = RedisScanUtils.normalizeCursor(cou.toString());
                }
                List<?> keys = RedisScanUtils.getKeys(resultSet.getObject(2));
                for (Object object : keys) {
                    RedisKey redisKey = new RedisKey();
                    redisKey.setName(object.toString());
                    String keyType = RedisScriptExecutor.getInstance().getKeyType(object.toString());
                    redisKey.setType(keyType);
                    ITypeScript typeScript = RedisDataType.fromCode(keyType).getScript();
                    redisKeys.add(typeScript.getKeyR(connection, redisKey));
                }
            }
            return nextCursor;
        });
        if (!RedisConstants.SCAN_INITIAL_CURSOR.equals(cursor)
                && redisKeys.size() < RedisScanConfig.DEFAULT.legacyTopCount()) {
            match(connection, redisKeys, pattern, cursor);
        }
    }

    private List<RedisKey> findTop(Connection connection) {
        String query = String.format(RedisCommandTemplates.SCAN_MATCH_COUNT, RedisConstants.SCAN_INITIAL_CURSOR,
                RedisValueUtils.getRedisValue("*"), RedisScanConfig.DEFAULT.legacyTopCount());
        return DefaultSQLExecutor.getInstance().execute(connection, query, resultSet -> {
            List<RedisKey> redisKeys = new ArrayList<>();
            while (resultSet.next()) {
                List<?> keys = RedisScanUtils.getKeys(resultSet.getObject(2));
                for (Object object : keys) {
                    RedisKey redisKey = findKey(connection, object.toString());
                    if (redisKey != null) {
                        redisKeys.add(redisKey);
                    }
                }
            }
            return redisKeys;
        });
    }


    @Override
    public PageResult<Table> tables(Connection connection, String databaseName, String schemaName, String tableName, int pageNo, int pageSize) {
        if (StringUtils.isBlank(tableName)) {
            return super.tables(connection, databaseName, schemaName, tableName, pageNo, pageSize);
        } else {
            List<Table> result = DefaultSQLExecutor.getInstance().execute(connection,
                    String.format(RedisConstants.COMMAND_EXISTS_KEY, RedisValueUtils.getRedisValue(tableName)), resultSet -> {
                List<Table> tables = new ArrayList<>();
                while (resultSet.next()) {
                    if (resultSet.getInt(1) == 1) {
                        Table table = new Table();
                        table.setName(tableName);
                        tables.add(table);
                    }
                }
                return tables;
            });
            result.sort(Comparator.comparing(Table::getName, String.CASE_INSENSITIVE_ORDER));
            return PageResult.of(result, (long) result.size(), pageNo, pageSize);
        }
    }

    @Override
    public ICommandExecutor getCommandExecutor() {
        return RedisScriptExecutor.getInstance();
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return RedisSqlBuilder.getInstance();
    }
}

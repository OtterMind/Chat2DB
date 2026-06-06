package ai.chat2db.server.domain.core.impl;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import ai.chat2db.server.domain.api.param.datasource.DatabaseCreateParam;
import ai.chat2db.server.domain.api.param.datasource.DatabaseQueryAllParam;
import ai.chat2db.server.domain.api.param.MetaDataQueryParam;
import ai.chat2db.server.domain.api.param.SchemaOperationParam;
import ai.chat2db.server.domain.api.param.SchemaQueryParam;
import ai.chat2db.server.domain.api.param.TablePageQueryParam;
import ai.chat2db.server.domain.api.param.TableSelector;
import ai.chat2db.server.domain.api.service.DatabaseService;
import ai.chat2db.server.domain.api.service.TableService;
import ai.chat2db.server.domain.core.cache.CacheManage;
import ai.chat2db.server.domain.core.cache.LuceneIndexManager;
import ai.chat2db.server.domain.core.cache.LuceneIndexManagerFactory;
import ai.chat2db.server.tools.base.enums.DataSourceTypeEnum;
import ai.chat2db.spi.MetaData;
import ai.chat2db.spi.SqlBuilder;
import ai.chat2db.spi.model.*;
import ai.chat2db.spi.sql.Chat2DBContext;
import cn.hutool.core.thread.ThreadUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.stream.Collectors;

import static ai.chat2db.server.domain.core.cache.CacheKey.getDataBasesKey;
import static ai.chat2db.server.domain.core.cache.CacheKey.getDataSourceKey;
import static ai.chat2db.server.domain.core.cache.CacheKey.getSchemasKey;

/**
 * @author moji
 * @version DataSourceCoreServiceImpl.java, v 0.1 2022年09月23日 15:51 moji Exp $
 * @date 2022/09/23
 */
@Slf4j
@Service
public class DatabaseServiceImpl implements DatabaseService {

    @Autowired
    private TableService tableService;

    @Autowired
    private LuceneIndexManagerFactory luceneIndexManagerFactory;

    @Override
    public List<Database> queryAll(DatabaseQueryAllParam param) {
        List<Database> databases = CacheManage.getList(getDataBasesKey(param.getDataSourceId()), Database.class,
                (key) -> param.isRefresh(),
                (key) -> getDatabases(param.getDbType(), getConnection(param.getDbType(), param.getConnection()))
        );
        return databases;
    }

    private Connection getConnection(String dbType, Connection connection) {
        if (connection != null) {
            return connection;
        }
        if (DataSourceTypeEnum.REDIS.getCode().equals(dbType)
                || DataSourceTypeEnum.REDIS.getCode().equals(Chat2DBContext.getConnectInfo().getDbType())) {
            return null;
        }
        return Chat2DBContext.getConnection();
    }

    private List<Database> getDatabases(String dbType, Connection connection) {
        return Chat2DBContext.getMetaData(dbType).databases(connection);
    }

    @Override
    public List<Schema> querySchema(SchemaQueryParam param) {
        List<Schema> schemas = CacheManage.getList(getSchemasKey(param.getDataSourceId(), param.getDataBaseName()),
                Schema.class,
                (key) -> param.isRefresh(), (key) -> {
                    Connection connection = getConnection(Chat2DBContext.getConnectInfo().getDbType(),
                            param.getConnection());
                    return getSchemaList(param.getDataBaseName(), connection);
                });
        return schemas;
    }


    private List<Schema> getSchemaList(String databaseName, Connection connection) {
        MetaData metaData = Chat2DBContext.getMetaData();
        List<Schema> schemas = metaData.schemas(connection, databaseName);
        sortSchema(schemas, connection);
        return schemas;
    }

    private void sortSchema(List<Schema> schemas, Connection connection) {
        if (CollectionUtils.isEmpty(schemas)) {
            return;
        }
        if (connection == null) {
            return;
        }
        String ulr = null;
        try {
            ulr = connection.getMetaData().getURL();
        } catch (SQLException e) {
            log.error("get url error", e);
        }
        // If the database name contains the name of the current database, the current database is placed in the first place
        int num = -1;
        for (int i = 0; i < schemas.size(); i++) {
            String schema = schemas.get(i).getName();
            if (StringUtils.isNotBlank(ulr) && schema != null && ulr.contains(schema)) {
                num = i;
                break;
            }
        }
        if (num != -1 && num != 0) {
            Collections.swap(schemas, num, 0);
        }
    }

    @Override
    public MetaSchema queryDatabaseSchema(MetaDataQueryParam param) {
        MetaSchema metaSchema = new MetaSchema();
        MetaData metaData = Chat2DBContext.getMetaData();
        MetaSchema ms = CacheManage.get(getDataSourceKey(param.getDataSourceId()), MetaSchema.class,
                (key) -> param.isRefresh(), (key) -> {
                    Connection connection = getConnection(Chat2DBContext.getConnectInfo().getDbType(), null);
                    List<Database> databases = metaData.databases(connection);
                    if (!CollectionUtils.isEmpty(databases)) {
                        CountDownLatch countDownLatch = ThreadUtil.newCountDownLatch(databases.size());
                        for (Database database : databases) {
                            ThreadUtil.execute(() -> {
                                try {
                                    database.setSchemas(metaData.schemas(connection, database.getName()));
                                    countDownLatch.countDown();
                                } catch (Exception e) {
                                    log.error("queryDatabaseSchema error", e);
                                }
                            });
                        }
                        try {
                            countDownLatch.await();
                        } catch (InterruptedException e) {
                            log.error("queryDatabaseSchema error", e);
                        }
                        metaSchema.setDatabases(databases);

                    } else {
                        List<Schema> schemas = metaData.schemas(connection, null);
                        metaSchema.setSchemas(schemas);
                    }
                    return metaSchema;
                });

        return ms;
    }

    private boolean isRedis() {
        return DataSourceTypeEnum.REDIS.getCode().equals(Chat2DBContext.getConnectInfo().getDbType());
    }

    @Override
    public void deleteDatabase(DatabaseCreateParam param) {
        String databaseName = param.getName();
        Long dataSourceId = Chat2DBContext.getConnectInfo().getDataSourceId();

        // 1. 执行 DROP DATABASE SQL
        Chat2DBContext.getDBManage().dropDatabase(Chat2DBContext.getConnection(), databaseName);

        // 2. 级联删除 Lucene 索引中该数据库相关的所有文档
        try {
            LuceneIndexManager<?> luceneMgr = luceneIndexManagerFactory.getManager(dataSourceId);
            luceneMgr.deleteByDatabaseAndSchema(databaseName, null);
        } catch (Exception e) {
            log.warn("Failed to clean Lucene index for database: {}", databaseName, e);
        }

        // 3. 清除 EHCache 中该数据库相关的缓存
        CacheManage.remove(getDataBasesKey(dataSourceId));
        CacheManage.remove(getSchemasKey(dataSourceId, databaseName));
    }

    @Override
    public Sql createDatabase(Database database) {
        String sql = Chat2DBContext.getSqlBuilder().buildCreateDatabaseSql(database);
        return Sql.builder().sql(sql).build();
    }

    @Override
    public void modifyDatabase(DatabaseCreateParam param) {
        Chat2DBContext.getDBManage().modifyDatabase(Chat2DBContext.getConnection(), param.getName(),
                param.getName());

    }

    @Override
    public void deleteSchema(SchemaOperationParam param) {
        Chat2DBContext.getDBManage().dropSchema(Chat2DBContext.getConnection(), param.getDatabaseName(),
                param.getSchemaName());

    }

    @Override
    public Sql createSchema(Schema schema) {
        String sql = Chat2DBContext.getSqlBuilder().buildCreateSchemaSql(schema);
        return Sql.builder().sql(sql).build();
    }

    @Override
    public void modifySchema(SchemaOperationParam param) {
        Chat2DBContext.getDBManage().modifySchema(Chat2DBContext.getConnection(), param.getDatabaseName(),
                param.getSchemaName(),
                param.getNewSchemaName());

    }

    @Override
    public String queryTableDdl(Long dataSourceId, String databaseName, String schemaName, String tableName) {
        try {
            TablePageQueryParam param = new TablePageQueryParam();
            param.setDataSourceId(dataSourceId);
            param.setDatabaseName(databaseName);
            param.setSchemaName(schemaName);
            param.setTableName(tableName);

            TableSelector tableSelector = new TableSelector();
            tableSelector.setColumnList(true);
            tableSelector.setIndexList(true);
            tableSelector.setForeignKey(true);

            List<Table> tables = tableService.pageQuery(param, tableSelector);
            if (!CollectionUtils.isEmpty(tables)) {
                SqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
                return sqlBuilder.buildCreateTableSql(tables.get(0));
            }
        } catch (Exception e) {
            log.error("query table ddl error, tableName:{}", tableName, e);
        }
        return "";
    }

    @Override
    public String buildTableColumn(Long dataSourceId, String databaseName, String schemaName, List<String> tableNames) {
        if (CollectionUtils.isEmpty(tableNames)) {
            log.error("tableNames is empty");
            return "";
        }
        try {
            return tableNames.stream()
                    .map(tableName -> queryTableDdl(dataSourceId, databaseName, schemaName, tableName))
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("query tables:{} error, do nothing", tableNames);
        }
        return "";
    }

    @Override
    public String queryDatabaseTables(Long dataSourceId, String databaseName, String schemaName) {
        try {
            TablePageQueryParam queryParam = new TablePageQueryParam();
            queryParam.setDataSourceId(dataSourceId);
            queryParam.setDatabaseName(databaseName);
            queryParam.setSchemaName(schemaName);
            queryParam.queryAll();

            TableSelector tableSelector = TableSelector.builder()
                    .indexList(false)
                    .columnList(true)
                    .foreignKey(true)
                    .build();

            List<Table> tables = tableService.pageQuery(queryParam, tableSelector);
            return tables.stream().map(table -> {
                StringBuilder sb = new StringBuilder(table.getName());
                String comment = StringUtils.defaultString(table.getComment(), table.getAiComment());
                List<ForeignKey> foreignKeys = table.getForeignKeyList();

                List<VirtualForeignKey> virtualForeignKeys = table.getVirtualForeignKeyList();
                if (StringUtils.isNotEmpty(comment) || !foreignKeys.isEmpty() || !virtualForeignKeys.isEmpty()) {
                    sb.append("(").append(comment);

                    if (!foreignKeys.isEmpty()) {
                        if (StringUtils.isNotEmpty(comment)) {
                            sb.append(";");
                        }
                        String foreignKeysString = foreignKeys.stream()
                                .map(foreignKey -> foreignKey.getColumn() + "->" +
                                        foreignKey.getReferencedTable() + ":" +
                                        foreignKey.getReferencedColumn())
                                .collect(Collectors.joining(","));

                        sb.append("foreignKeys:").append(foreignKeysString);
                    }
                    if (!virtualForeignKeys.isEmpty()) {
                        if (StringUtils.isNotEmpty(comment) || !foreignKeys.isEmpty()) {
                            sb.append(";");
                        }
                        String virtualForeignKeysString = virtualForeignKeys.stream()
                                .map(virtualForeignKey -> virtualForeignKey.getColumn() + "->" +
                                        virtualForeignKey.getReferencedTable() + ":" +
                                        virtualForeignKey.getReferencedColumn())
                                .collect(Collectors.joining(","));
                        sb.append("virtualForeignKeys:").append(virtualForeignKeysString);
                    }
                    sb.append(")");
                }
                return sb.toString();
            }).collect(Collectors.joining(","));
        } catch (Exception e) {
            log.error("query table error:{}, do nothing", e.getMessage());
            return "";
        }
    }

    @Override
    public String queryRedisSchema(Long dataSourceId, String databaseName, String schemaName, List<String> tableNames) {
        if (CollectionUtils.isEmpty(tableNames)) {
            SchemaQueryParam param = new SchemaQueryParam();
            param.setDataSourceId(dataSourceId);
            param.setDataBaseName(databaseName);

            List<Schema> schemaListResult = querySchema(param);
            List<String> keyNames = new ArrayList<>();
            String properties = schemaListResult
                    .stream()
                    .peek(schema -> keyNames.add(schema.getName()))
                    .map(schema -> schema.getName() + ":*(" + schema.getKeyType() + ")")
                    .collect(Collectors.joining(","));
            return properties;
        }
        return tableNames.stream()
                .map(name -> name + ":*")
                .collect(Collectors.joining(","));
    }


}

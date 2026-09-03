package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.request.db.DbMetaDataQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSchemaOperationRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSchemaQueryRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseCreateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseQueryAllRequest;
import ai.chat2db.community.domain.api.model.metadata.extension.MetadataAccessContext;
import ai.chat2db.community.domain.api.service.db.IDbDatabaseService;
import ai.chat2db.community.domain.core.cache.CacheManage;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import ai.chat2db.community.domain.core.util.ListSorter;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.MetaSchema;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.sql.Sql;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static ai.chat2db.community.domain.core.cache.CacheKey.*;


@Slf4j
@Service
public class DbDatabaseServiceImpl implements IDbDatabaseService {

    private final MetadataAccessPolicyManager metadataAccessPolicyManager;

    public DbDatabaseServiceImpl() {
        this(new MetadataAccessPolicyManager(List.of()));
    }

    @Autowired
    public DbDatabaseServiceImpl(MetadataAccessPolicyManager metadataAccessPolicyManager) {
        this.metadataAccessPolicyManager = metadataAccessPolicyManager;
    }

    @Override
    public List<Database> queryAll(DbDatabaseQueryAllRequest param) {
        try {
            String cacheKey = getDataBasesKey(param.getDataSourceId());
            List<Database> databases = CacheManage.getList(cacheKey, Database.class,
                    (key) -> param.isRefresh(),
                    (key) -> getDatabases(param.getDbType(), param.getConnection() == null ? Chat2DBContext.getConnection()
                            : param.getConnection())
            );
            ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
            if (Objects.nonNull(connectInfo) && !"REDIS".equalsIgnoreCase(connectInfo.getDbType())) {
                ListSorter.sortByKey(databases, Database::getName);
            }
            String dbType = StringUtils.defaultIfBlank(param.getDbType(), currentDbType());
            return metadataAccessPolicyManager.filter(databases,
                    database -> resource(param.getDataSourceId(), dbType, database.getName(), null, null, null));
        } catch (Exception e) {
            if (!param.isRefresh()) {
                log.error("database.list.fallback", e);
                return Collections.emptyList();
            }
            throw e;
        }
    }

    @Override
    public List<Schema> querySchema(DbSchemaQueryRequest param) {
        try {
            String cacheKey = getSchemasKey(param.getDataSourceId(), param.getDataBaseName());
            List<Schema> schemas = CacheManage.getList(cacheKey,
                    Schema.class,
                    (key) -> param.isRefresh(), (key) -> {
                        Connection connection = param.getConnection() == null ? Chat2DBContext.getConnection()
                                : param.getConnection();
                        return getSchemaList(param.getDataBaseName(), connection);
                    });
            ListSorter.sortByKey(schemas, Schema::getName);
            return metadataAccessPolicyManager.filter(schemas,
                    schema -> resource(param.getDataSourceId(), currentDbType(), param.getDataBaseName(),
                            schema.getName(), null, null));
        } catch (Exception e) {
            if (!param.isRefresh()) {
                log.error("schema.list.fallback", e);
                return Collections.emptyList();
            }
            throw e;
        }
    }

    @Override
    public MetaSchema queryDatabaseSchema(DbMetaDataQueryRequest param) {
        MetaSchema metaSchema = new MetaSchema();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        MetaSchema ms = CacheManage.get(getDataSourceKey(param.getDataSourceId()), MetaSchema.class,
                (key) -> param.isRefresh(), (key) -> {
                    Connection connection = Chat2DBContext.getConnection();
                    List<Database> databases = metaData.databases(connection);
                    if (!CollectionUtils.isEmpty(databases)) {
                        for (Database database : databases) {
                            try {
                                database.setSchemas(metaData.schemas(connection, database.getName()));
                            } catch (Exception e) {
                                throw new IllegalStateException(
                                        "Failed to query schemas for database " + database.getName(), e);
                            }
                        }
                        metaSchema.setDatabases(databases);
                    } else {
                        List<Schema> schemas = metaData.schemas(connection, null);
                        metaSchema.setSchemas(schemas);
                    }
                    return metaSchema;
                });

        return filterMetaSchema(param.getDataSourceId(), ms);
    }

    @Override
    public void deleteDatabase(DbDatabaseCreateRequest param) {
        Chat2DBContext.getDbManager().dropDatabase(Chat2DBContext.getConnection(), param.getName());
    }

    @Override
    public Sql createDatabase(Database database) {
        String sql = Chat2DBContext.getSqlBuilder().ddl().database().buildCreateDatabase(database);
        return Sql.builder().sql(sql).build();
    }

    @Override
    public void modifyDatabase(DbDatabaseCreateRequest param) {
        Chat2DBContext.getDbManager().modifyDatabase(Chat2DBContext.getConnection(), param.getName(),
                param.getNewName());
    }

    @Override
    public void deleteSchema(DbSchemaOperationRequest param) {
        Chat2DBContext.getDbManager().dropSchema(Chat2DBContext.getConnection(), param.getDatabaseName(),
                param.getSchemaName());
    }

    @Override
    public Sql createSchema(Schema schema) {
        String sql = Chat2DBContext.getSqlBuilder().ddl().schema().buildCreateSchema(schema);
        return Sql.builder().sql(sql).build();
    }

    @Override
    public void modifySchema(DbSchemaOperationRequest param) {
        Chat2DBContext.getDbManager().modifySchema(Chat2DBContext.getConnection(), param.getDatabaseName(),
                param.getSchemaName(), param.getNewSchemaName());
    }

    private List<Database> getDatabases(String dbType, Connection connection) {
        return Chat2DBContext.getDbMetaData(dbType).databases(connection);
    }

    private List<Schema> getSchemaList(String databaseName, Connection connection) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        List<Schema> schemas = metaData.schemas(connection, databaseName);
        sortSchema(schemas, connection);
        return schemas;
    }

    private void sortSchema(List<Schema> schemas, Connection connection) {
        if (CollectionUtils.isEmpty(schemas)) {
            return;
        }
        String ulr;
        try {
            ulr = connection.getMetaData().getURL();
        } catch (SQLException e) {
            log.debug("JDBC driver does not expose its URL; skip schema ordering", e);
            return;
        }
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

    private MetaSchema filterMetaSchema(Long dataSourceId, MetaSchema source) {
        if (source == null || metadataAccessPolicyManager.isEmpty()) {
            return source;
        }
        String dbType = currentDbType();
        MetaSchema filtered = new MetaSchema();
        if (!CollectionUtils.isEmpty(source.getDatabases())) {
            List<Database> databases = metadataAccessPolicyManager.filter(source.getDatabases(),
                    database -> resource(dataSourceId, dbType, database.getName(), null, null, null));
            filtered.setDatabases(databases.stream().map(database -> {
                Database copy = copyDatabase(database);
                if (!CollectionUtils.isEmpty(database.getSchemas())) {
                    copy.setSchemas(metadataAccessPolicyManager.filter(database.getSchemas(),
                            schema -> resource(dataSourceId, dbType, database.getName(), schema.getName(), null,
                                    null)));
                }
                return copy;
            }).toList());
        }
        if (!CollectionUtils.isEmpty(source.getSchemas())) {
            filtered.setSchemas(metadataAccessPolicyManager.filter(source.getSchemas(),
                    schema -> resource(dataSourceId, dbType, schema.getDatabaseName(), schema.getName(), null,
                            null)));
        }
        return filtered;
    }

    private Database copyDatabase(Database source) {
        Database copy = new Database();
        copy.setName(source.getName());
        copy.setComment(source.getComment());
        copy.setCharset(source.getCharset());
        copy.setCollation(source.getCollation());
        copy.setOwner(source.getOwner());
        copy.setSystem(source.isSystem());
        copy.setSchemas(source.getSchemas());
        return copy;
    }

    private MetadataAccessContext resource(Long dataSourceId, String dbType, String databaseName,
            String schemaName, String tableName, String columnName) {
        return MetadataAccessContext.builder()
                .dataSourceId(dataSourceId)
                .dbType(dbType)
                .databaseName(databaseName)
                .schemaName(schemaName)
                .tableName(tableName)
                .columnName(columnName)
                .operationType("SELECT")
                .build();
    }

    private String currentDbType() {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return connectInfo == null ? null : connectInfo.getDbType();
    }

}

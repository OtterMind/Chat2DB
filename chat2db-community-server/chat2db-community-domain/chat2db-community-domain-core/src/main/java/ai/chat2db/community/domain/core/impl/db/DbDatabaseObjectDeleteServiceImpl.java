package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Tablespace;
import ai.chat2db.community.domain.api.model.db.DatabaseObjectDeletePrepare;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.request.db.DbDatabaseDeletePrepareRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDatabaseObjectDeleteExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSchemaDeletePrepareRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTablespaceDeletePrepareRequest;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbDatabaseObjectDeleteService;
import ai.chat2db.community.domain.core.cache.CacheKey;
import ai.chat2db.community.domain.core.cache.CacheManage;
import ai.chat2db.community.domain.core.cache.MemoryCacheManage;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

@Service
public class DbDatabaseObjectDeleteServiceImpl implements IDbDatabaseObjectDeleteService {

    private static final String TYPE_DATABASE = "DATABASE";

    private static final String TYPE_SCHEMA = "SCHEMA";

    private static final String TYPE_TABLESPACE = "TABLESPACE";

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            DatabaseTypeEnum.MYSQL.name(),
            DatabaseTypeEnum.POSTGRESQL.name()
    );

    private static final Set<String> MYSQL_SYSTEM_DATABASES = Set.of(
            "information_schema",
            "mysql",
            "performance_schema",
            "sys"
    );

    private static final Set<String> POSTGRESQL_SYSTEM_DATABASES = Set.of(
            "postgres",
            "template0",
            "template1"
    );

    private static final Set<String> POSTGRESQL_SYSTEM_SCHEMAS = Set.of(
            "pg_catalog",
            "information_schema",
            "public"
    );

    private final IDbConnectionContextService connectionContextService;

    private final TablespaceMetadataAccessFilter tablespaceMetadataAccessFilter;

    public DbDatabaseObjectDeleteServiceImpl(IDbConnectionContextService connectionContextService) {
        this(connectionContextService, new MetadataAccessPolicyManager(List.of()));
    }

    @Autowired
    public DbDatabaseObjectDeleteServiceImpl(IDbConnectionContextService connectionContextService,
                                              MetadataAccessPolicyManager metadataAccessPolicyManager) {
        this.connectionContextService = connectionContextService;
        this.tablespaceMetadataAccessFilter = new TablespaceMetadataAccessFilter(metadataAccessPolicyManager);
    }

    @Override
    public DatabaseObjectDeletePrepare prepareDatabaseDelete(DbDatabaseDeletePrepareRequest param) {
        ConnectionProfile profile = requireCurrentProfile();
        String dbType = requireSupportedDbType(profile);
        reconnectForDatabaseDelete(dbType);
        String databaseName = requireName(param.getDatabaseName(), "databaseName");
        assertDatabaseDeletionSupported(dbType);
        rejectSystemDatabase(dbType, databaseName);
        Connection connection = requireConnection();
        try {
            assertDatabaseExists(connection, dbType, databaseName);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("database.delete.prepareFailed", new Object[]{e.getMessage()}, e);
        }
        return prepareResult(databaseConfirmName(databaseName), buildDropDatabase(dbType, databaseName),
                TYPE_DATABASE, dbType);
    }

    @Override
    public DatabaseObjectDeletePrepare prepareSchemaDelete(DbSchemaDeletePrepareRequest param) {
        ConnectionProfile profile = requireCurrentProfile();
        String dbType = requireSupportedDbType(profile);
        String databaseName = requireName(param.getDatabaseName(), "databaseName");
        String schemaName = requireName(param.getSchemaName(), "schemaName");
        assertSchemaDeletionSupported(dbType);
        rejectSystemSchema(dbType, schemaName);
        Connection connection = requireConnection();
        try {
            assertSchemaExists(connection, databaseName, schemaName);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("database.delete.prepareFailed", new Object[]{e.getMessage()}, e);
        }
        return prepareResult(schemaConfirmName(schemaName), buildDropSchema(dbType, schemaName), TYPE_SCHEMA, dbType);
    }

    @Override
    public void executeDatabaseDelete(DbDatabaseObjectDeleteExecuteRequest param) {
        ConnectionProfile profile = requireCurrentProfile();
        String dbType = requireSupportedDbType(profile);
        reconnectForDatabaseDelete(dbType);
        profile = requireCurrentProfile();
        String databaseName = requireName(param.getDatabaseName(), "databaseName");
        assertDatabaseDeletionSupported(dbType);
        rejectSystemDatabase(dbType, databaseName);
        assertConfirmName(param.getConfirmName(), databaseConfirmName(databaseName));
        if (StringUtils.equals(profile.getDatabaseName(), databaseName)) {
            throw new BusinessException("database.delete.connectionTargetsDeletedDatabase");
        }
        Connection connection = requireConnection();
        try {
            assertDatabaseExists(connection, dbType, databaseName);
            Chat2DBContext.getDbManager(dbType).dropDatabase(connection, databaseName);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("database.delete.executeFailed", new Object[]{e.getMessage()}, e);
        }
    }

    @Override
    public void executeSchemaDelete(DbDatabaseObjectDeleteExecuteRequest param) {
        ConnectionProfile profile = requireCurrentProfile();
        String dbType = requireSupportedDbType(profile);
        String databaseName = requireName(param.getDatabaseName(), "databaseName");
        String schemaName = requireName(param.getSchemaName(), "schemaName");
        assertSchemaDeletionSupported(dbType);
        rejectSystemSchema(dbType, schemaName);
        assertConfirmName(param.getConfirmName(), schemaConfirmName(schemaName));
        if (!StringUtils.equals(profile.getDatabaseName(), databaseName)) {
            throw new BusinessException("database.delete.connectionDatabaseMismatch");
        }
        Connection connection = requireConnection();
        try {
            assertSchemaExists(connection, databaseName, schemaName);
            Chat2DBContext.getDbManager(dbType).dropSchema(connection, databaseName, schemaName);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("database.delete.executeFailed", new Object[]{e.getMessage()}, e);
        }
    }

    @Override
    public DatabaseObjectDeletePrepare prepareTablespaceDelete(DbTablespaceDeletePrepareRequest param) {
        ConnectionProfile profile = requireCurrentProfile();
        String dbType = requireSupportedDbType(profile);
        String tablespaceName = requireName(param.getTablespaceName(), "tablespaceName");
        assertTablespaceManagementSupported(dbType);
        Connection connection = requireConnection();
        try {
            Tablespace tablespace = Chat2DBContext.getDbMetaData(dbType).tablespace(connection, tablespaceName);
            if (tablespace == null) {
                throw new BusinessException("tablespace.delete.notExists");
            }
            String sqlPreview = Chat2DBContext.getDbMetaData(dbType).getSqlBuilder().ddl().tablespace()
                    .buildDropTablespace(tablespaceName);
            return DatabaseObjectDeletePrepare.builder()
                    .confirmName(tablespaceName)
                    .sqlPreview(sqlPreview)
                    .objectType(TYPE_TABLESPACE)
                    .dbType(dbType)
                    .occupyingTables(tablespaceMetadataAccessFilter.filterOccupyingTables(
                            param.getDataSourceId(), dbType, tablespace.getOccupyingTables()))
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("database.delete.prepareFailed", new Object[]{e.getMessage()}, e);
        }
    }

    @Override
    public void executeTablespaceDelete(DbDatabaseObjectDeleteExecuteRequest param) {
        ConnectionProfile profile = requireCurrentProfile();
        String dbType = requireSupportedDbType(profile);
        String tablespaceName = requireName(param.getTablespaceName(), "tablespaceName");
        assertTablespaceManagementSupported(dbType);
        assertConfirmName(param.getConfirmName(), tablespaceName);
        Connection connection = requireConnection();
        try {
            // Defense-in-depth: re-check occupancy immediately before dropping. MySQL itself also
            // rejects a non-empty tablespace (ER_TABLESPACE_NOT_EMPTY), but surfacing the list here
            // gives a clearer error than the raw driver exception.
            Tablespace tablespace = Chat2DBContext.getDbMetaData(dbType).tablespace(connection, tablespaceName);
            if (tablespace == null) {
                throw new BusinessException("tablespace.delete.notExists");
            }
            if (CollectionUtils.isNotEmpty(tablespace.getOccupyingTables())) {
                throw new BusinessException("tablespace.delete.notEmpty");
            }
            Chat2DBContext.getDbManager(dbType).dropTablespace(connection, tablespaceName);
            invalidateTablespaceCache(param.getDataSourceId());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("database.delete.executeFailed", new Object[]{e.getMessage()}, e);
        }
    }

    private void assertTablespaceManagementSupported(String dbType) {
        DBConfig dbConfig = Chat2DBContext.getDBConfig(dbType);
        if (dbConfig == null || !dbConfig.isSupportTablespace()
                || !Chat2DBContext.getDbManager(dbType).supportsTablespaceManagement()) {
            throw new BusinessException("tablespace.notSupported");
        }
    }

    private void invalidateTablespaceCache(Long dataSourceId) {
        if (dataSourceId == null) {
            return;
        }
        String cacheKey = CacheKey.getTablespacesKey(dataSourceId);
        CacheManage.remove(cacheKey);
        MemoryCacheManage.remove(cacheKey);
    }

    private ConnectionProfile requireCurrentProfile() {
        ConnectionProfile profile = connectionContextService.currentProfile();
        if (profile == null) {
            throw new BusinessException("database.delete.invalidDataSource");
        }
        return profile;
    }

    private String requireSupportedDbType(ConnectionProfile profile) {
        String dbType = StringUtils.upperCase(StringUtils.trimToNull(profile.getDbType()));
        if (!SUPPORTED_TYPES.contains(dbType)) {
            throw new BusinessException("database.delete.notSupportDbType");
        }
        return dbType;
    }

    private void reconnectForDatabaseDelete(String dbType) {
        if (DatabaseTypeEnum.MYSQL.name().equalsIgnoreCase(dbType)) {
            connectionContextService.rebindCurrentDatabase(null);
            return;
        }
        if (DatabaseTypeEnum.POSTGRESQL.name().equalsIgnoreCase(dbType)) {
            connectionContextService.rebindCurrentDatabase(maintenanceDatabase());
        }
    }

    private String requireName(String name, String fieldName) {
        String trimmed = StringUtils.trimToNull(name);
        if (trimmed == null) {
            throw new BusinessException("database.delete." + fieldName + "Required");
        }
        return trimmed;
    }

    private void assertDatabaseDeletionSupported(String dbType) {
        DBConfig dbConfig = Chat2DBContext.getDBConfig(dbType);
        if (dbConfig == null || !dbConfig.isSupportDatabase()) {
            throw new BusinessException("database.delete.notSupportDatabase");
        }
    }

    private void rejectSystemDatabase(String dbType, String databaseName) {
        if (DatabaseTypeEnum.MYSQL.name().equalsIgnoreCase(dbType)
                && MYSQL_SYSTEM_DATABASES.contains(StringUtils.lowerCase(databaseName))) {
            throw new BusinessException("database.delete.systemDatabaseForbidden");
        }
        if (DatabaseTypeEnum.POSTGRESQL.name().equalsIgnoreCase(dbType)
                && POSTGRESQL_SYSTEM_DATABASES.contains(StringUtils.lowerCase(databaseName))) {
            throw new BusinessException("database.delete.systemDatabaseForbidden");
        }
    }

    private Connection requireConnection() {
        Connection connection = Chat2DBContext.getConnection();
        if (connection == null) {
            throw new BusinessException("database.delete.connectionFailed");
        }
        return connection;
    }

    private void assertDatabaseExists(Connection connection, String dbType, String databaseName) {
        List<Database> databases = Chat2DBContext.getDbMetaData(dbType).databases(connection);
        boolean exists = databases != null && databases.stream()
                .anyMatch(database -> StringUtils.equals(database.getName(), databaseName));
        if (!exists) {
            throw new BusinessException("database.delete.databaseNotExists");
        }
    }

    private DatabaseObjectDeletePrepare prepareResult(String confirmName, String sqlPreview, String objectType,
                                                       String dbType) {
        return DatabaseObjectDeletePrepare.builder()
                .confirmName(confirmName)
                .sqlPreview(sqlPreview)
                .objectType(objectType)
                .dbType(dbType)
                .build();
    }

    private String databaseConfirmName(String databaseName) {
        return databaseName;
    }

    private String buildDropDatabase(String dbType, String databaseName) {
        return Chat2DBContext.getDbMetaData(dbType).getSqlBuilder().ddl().database().buildDropDatabase(databaseName);
    }

    private void assertSchemaDeletionSupported(String dbType) {
        DBConfig dbConfig = Chat2DBContext.getDBConfig(dbType);
        if (dbConfig == null || !dbConfig.isSupportSchema()
                || !DatabaseTypeEnum.POSTGRESQL.name().equalsIgnoreCase(dbType)) {
            throw new BusinessException("database.delete.notSupportSchema");
        }
    }

    private void rejectSystemSchema(String dbType, String schemaName) {
        if (DatabaseTypeEnum.POSTGRESQL.name().equalsIgnoreCase(dbType)) {
            String lowerSchemaName = StringUtils.lowerCase(schemaName);
            if (POSTGRESQL_SYSTEM_SCHEMAS.contains(lowerSchemaName)
                    || StringUtils.startsWith(lowerSchemaName, "pg_toast")
                    || StringUtils.startsWith(lowerSchemaName, "pg_temp")) {
                throw new BusinessException("database.delete.systemSchemaForbidden");
            }
        }
    }

    private void assertSchemaExists(Connection connection, String databaseName, String schemaName) {
        List<Schema> schemas = Chat2DBContext.getDbMetaData().schemas(connection, databaseName);
        boolean exists = schemas != null && schemas.stream()
                .anyMatch(schema -> StringUtils.equals(schema.getName(), schemaName));
        if (!exists) {
            throw new BusinessException("database.delete.schemaNotExists");
        }
    }

    private String schemaConfirmName(String schemaName) {
        return schemaName;
    }

    private String buildDropSchema(String dbType, String schemaName) {
        return Chat2DBContext.getDbMetaData(dbType).getSqlBuilder().ddl().schema().buildDropSchema(schemaName);
    }

    private void assertConfirmName(String actualConfirmName, String expectedConfirmName) {
        if (!StringUtils.equals(actualConfirmName, expectedConfirmName)) {
            throw new BusinessException("database.delete.confirmNameMismatch");
        }
    }

    private String maintenanceDatabase() {
        return "postgres";
    }
}

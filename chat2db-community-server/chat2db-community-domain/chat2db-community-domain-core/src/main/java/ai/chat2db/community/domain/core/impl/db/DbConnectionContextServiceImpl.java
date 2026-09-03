package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.runtime.McpConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbObjectsQueryRequest;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.core.converter.ConnectionContextConverter;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.model.request.ViewMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import ai.chat2db.community.domain.api.enums.plugin.ObjectTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class DbConnectionContextServiceImpl implements IDbConnectionContextService {

    @Autowired
    private IDbWorkspaceDataSourceService workspaceDataSourceService;

    @Autowired
    private ConnectionContextConverter connectionContextConverter;

    @Override
    public void bind(DbConnectionContextRequest param) {
        Chat2DBContext.putContext(buildConnectInfo(param));
    }

    @Override
    public ConnectionProfile buildProfile(DbConnectionContextRequest param) {
        return connectionContextConverter.connectInfo2profile(buildConnectInfo(param));
    }

    @Override
    public void bindProfile(ConnectionProfile profile) {
        ConnectInfo connectInfo;
        if (profile != null && profile.getDataSourceId() != null && profile.getDataSourceId() > 0) {
            DbConnectionContextRequest param = new DbConnectionContextRequest();
            param.setDataSourceId(profile.getDataSourceId());
            param.setConsoleId(profile.getConsoleId());
            param.setDatabaseName(profile.getDatabaseName());
            param.setSchemaName(profile.getSchemaName());
            connectInfo = buildConnectInfo(param);
        } else {
            connectInfo = connectionContextConverter.profile2connectInfo(profile);
        }
        if (connectInfo != null) {
            Chat2DBContext.putContext(connectInfo);
        }
    }

    @Override
    public void bindMcp(McpConnectionContextRequest param) {
        Chat2DBContext.putContext(buildMcpConnectInfo(param));
    }

    @Override
    public void clear() {
        Chat2DBContext.removeContext();
    }

    @Override
    public void rebindCurrentDatabase(String databaseName) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            throw new BusinessException("connection error");
        }
        Chat2DBContext.removeContext();
        connectInfo.setDatabaseName(databaseName);
        connectInfo.setConnection(null);
        Chat2DBContext.putContext(connectInfo);
    }

    @Override
    public void close() {
        ConnectionPool.removeConnection(Chat2DBContext.getConnectInfo());
        Chat2DBContext.close();
    }

    @Override
    public ConnectionProfile currentProfile() {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            return null;
        }
        return connectionContextConverter.connectInfo2profile(connectInfo);
    }

    @Override
    public ConnectionProfile currentProfileSnapshot() {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            return null;
        }
        return connectionContextConverter.connectInfo2profile(connectInfo.copy());
    }

    @Override
    public DriverConfig getDefaultDriverConfig(String dbType) {
        return Chat2DBContext.getDefaultDriverConfig(dbType);
    }

    @Override
    public boolean supportCrossDatabase() {
        return Chat2DBContext.getDbMetaData().supportCrossDatabase();
    }

    @Override
    public boolean supportCrossSchema() {
        return Chat2DBContext.getDbMetaData().supportCrossSchema();
    }

    @Override
    public boolean supportDatabase() {
        return Chat2DBContext.getDBConfig().isSupportDatabase();
    }

    @Override
    public boolean supportSchema() {
        return Chat2DBContext.getDBConfig().isSupportSchema();
    }

    @Override
    public List<String> getSystemDatabases(String dbType) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData(dbType);
        if (metaData == null) {
            return Collections.emptyList();
        }
        return Objects.requireNonNullElse(metaData.getSystemDatabases(), Collections.emptyList());
    }

    @Override
    public List<String> getSystemSchemas(String dbType) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData(dbType);
        if (metaData == null) {
            return Collections.emptyList();
        }
        return Objects.requireNonNullElse(metaData.getSystemSchemas(), Collections.emptyList());
    }

    @Override
    public List<ForeignKeyInfo> getImportedKeys(String databaseName, String schemaName, String tableName) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        if (metaData == null) {
            return Collections.emptyList();
        }
        return metaData.getImportedKeys(Chat2DBContext.getConnection(),
                new TableMetadataRequest(databaseName, schemaName, tableName));
    }

    @Override
    public List<ai.chat2db.community.domain.api.model.metadata.Table> queryObjects(
            DbObjectsQueryRequest queryObjectsRequest) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        String databaseName = queryObjectsRequest == null ? null : queryObjectsRequest.getDatabaseName();
        String schemaName = queryObjectsRequest == null ? null : queryObjectsRequest.getSchemaName();
        String objectName = queryObjectsRequest == null ? null : queryObjectsRequest.getObjectName();
        String objectType = queryObjectsRequest == null ? null : queryObjectsRequest.getObjectType();
        if (metaData == null || StringUtils.isBlank(objectName)) {
            return Collections.emptyList();
        }
        List<ai.chat2db.community.domain.api.model.metadata.Table> tables;
        if (ObjectTypeEnum.VIEW.name().equalsIgnoreCase(objectType)) {
            tables = metaData.views(Chat2DBContext.getConnection(),
                    new ViewMetadataRequest(databaseName, schemaName, objectName));
        } else {
            tables = metaData.tables(Chat2DBContext.getConnection(),
                    new TablesRequest(databaseName, schemaName, objectName));
        }
        if (tables == null) {
            return Collections.emptyList();
        }
        return tables.stream().filter(Objects::nonNull).toList();
    }

    private ConnectInfo buildConnectInfo(DbConnectionContextRequest param) {
        WorkspaceDataSource dataSource = workspaceDataSourceService.queryDisplayDataSourceById(param.getDataSourceId(), true);
        if (dataSource == null) {
            log.info("query datasource failed:{}", param.getDataSourceId());
            throw new BusinessException("datasource.not.found");
        }
        return connectionContextConverter.datasource2connectInfo(param, dataSource, dataSource.getUrl());
    }

    private ConnectInfo buildMcpConnectInfo(McpConnectionContextRequest param) {
        return connectionContextConverter.mcpParam2connectInfo(param, connectionContextConverter.buildMcpDataSourceId(param));
    }

}

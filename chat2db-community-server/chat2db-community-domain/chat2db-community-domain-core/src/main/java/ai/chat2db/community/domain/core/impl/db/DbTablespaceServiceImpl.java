package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.TablespaceCapability;
import ai.chat2db.community.domain.api.model.metadata.Tablespace;
import ai.chat2db.community.domain.api.model.request.datasource.DbTablespaceCreateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbTablespaceModifyRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbTablespaceQueryRequest;
import ai.chat2db.community.domain.api.model.sql.Sql;
import ai.chat2db.community.domain.api.service.db.IDbTablespaceService;
import ai.chat2db.community.domain.core.cache.CacheKey;
import ai.chat2db.community.domain.core.cache.CacheManage;
import ai.chat2db.community.domain.core.cache.MemoryCacheManage;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.sql.Chat2DBContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.Collections;
import java.util.List;

import static ai.chat2db.community.domain.core.cache.CacheKey.getTablespacesKey;

@Slf4j
@Service
public class DbTablespaceServiceImpl implements IDbTablespaceService {

    private final TablespaceMetadataAccessFilter metadataAccessFilter;

    public DbTablespaceServiceImpl() {
        this(new MetadataAccessPolicyManager(List.of()));
    }

    @Autowired
    public DbTablespaceServiceImpl(MetadataAccessPolicyManager metadataAccessPolicyManager) {
        this.metadataAccessFilter = new TablespaceMetadataAccessFilter(metadataAccessPolicyManager);
    }

    @Override
    public List<Tablespace> queryAll(DbTablespaceQueryRequest param) {
        if (!capability(param).isManageSupported()) {
            return Collections.emptyList();
        }
        try {
            String cacheKey = getTablespacesKey(param.getDataSourceId());
            if (param.isRefresh()) {
                MemoryCacheManage.remove(cacheKey);
            }
            List<Tablespace> tablespaces = CacheManage.getList(cacheKey, Tablespace.class,
                    (key) -> param.isRefresh(),
                    (key) -> getTablespaces(param.getDbType(),
                            param.getConnection() == null ? Chat2DBContext.getConnection() : param.getConnection())
            );
            return metadataAccessFilter.filter(param.getDataSourceId(), currentDbType(param), tablespaces);
        } catch (Exception e) {
            if (!param.isRefresh()) {
                log.error("tablespace.list.fallback", e);
                return Collections.emptyList();
            }
            throw e;
        }
    }

    @Override
    public Tablespace query(DbTablespaceQueryRequest param) {
        assertTablespaceManagementSupported();
        Connection connection = param.getConnection() == null ? Chat2DBContext.getConnection() : param.getConnection();
        Tablespace tablespace = Chat2DBContext.getDbMetaData(param.getDbType())
                .tablespace(connection, param.getTablespaceName());
        return metadataAccessFilter.filter(param.getDataSourceId(), currentDbType(param), tablespace);
    }

    @Override
    public Sql createTablespace(DbTablespaceCreateRequest param) {
        assertTablespaceManagementSupported();
        Tablespace tablespace = Tablespace.builder()
                .name(param.getName())
                .dataFiles(List.of(param.getDataFile()))
                .fileBlockSize(param.getFileBlockSize())
                .build();
        String sql = Chat2DBContext.getSqlBuilder().ddl().tablespace().buildCreateTablespace(tablespace);
        return Sql.builder().sql(sql).build();
    }

    @Override
    public Sql modifyTablespaceSql(DbTablespaceModifyRequest param) {
        assertTablespaceRenameSupported();
        String sql = Chat2DBContext.getSqlBuilder().ddl().tablespace()
                .buildRenameTablespace(param.getOldName(), param.getNewName());
        return Sql.builder().sql(sql).build();
    }

    @Override
    public void modifyTablespace(DbTablespaceModifyRequest param) {
        assertTablespaceRenameSupported();
        Chat2DBContext.getDbManager().alterTablespaceRename(Chat2DBContext.getConnection(),
                param.getOldName(), param.getNewName());
        invalidateTablespaceCache(param.getDataSourceId());
    }

    @Override
    public TablespaceCapability capability(DbTablespaceQueryRequest param) {
        String dbVersion = Chat2DBContext.getDbVersion();
        return TablespaceCapability.builder()
                .manageSupported(Chat2DBContext.getDbManager().supportsTablespaceManagement())
                .renameSupported(Chat2DBContext.getDbManager().supportsTablespaceRename())
                .serverVersion(dbVersion)
                .build();
    }

    private void assertTablespaceManagementSupported() {
        if (!Chat2DBContext.getDbManager().supportsTablespaceManagement()) {
            throw new BusinessException("tablespace.notSupported");
        }
    }

    private void assertTablespaceRenameSupported() {
        if (!Chat2DBContext.getDbManager().supportsTablespaceRename()) {
            throw new BusinessException("tablespace.rename.notSupported");
        }
    }

    private List<Tablespace> getTablespaces(String dbType, Connection connection) {
        return Chat2DBContext.getDbMetaData(dbType).tablespaces(connection);
    }

    private String currentDbType(DbTablespaceQueryRequest param) {
        if (param.getDbType() != null) {
            return param.getDbType();
        }
        return Chat2DBContext.getConnectInfo() == null ? null : Chat2DBContext.getConnectInfo().getDbType();
    }

    private void invalidateTablespaceCache(Long dataSourceId) {
        if (dataSourceId == null) {
            return;
        }
        String cacheKey = CacheKey.getTablespacesKey(dataSourceId);
        CacheManage.remove(cacheKey);
        MemoryCacheManage.remove(cacheKey);
    }
}

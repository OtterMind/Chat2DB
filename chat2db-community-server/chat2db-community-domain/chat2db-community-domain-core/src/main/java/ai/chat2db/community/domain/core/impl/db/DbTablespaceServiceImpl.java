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
import ai.chat2db.spi.sql.Chat2DBContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.Collections;
import java.util.List;

import static ai.chat2db.community.domain.core.cache.CacheKey.getTablespacesKey;

@Slf4j
@Service
public class DbTablespaceServiceImpl implements IDbTablespaceService {

    @Override
    public List<Tablespace> queryAll(DbTablespaceQueryRequest param) {
        try {
            String cacheKey = getTablespacesKey(param.getDataSourceId());
            List<Tablespace> tablespaces = CacheManage.getList(cacheKey, Tablespace.class,
                    (key) -> param.isRefresh(),
                    (key) -> getTablespaces(param.getDbType(),
                            param.getConnection() == null ? Chat2DBContext.getConnection() : param.getConnection())
            );
            return tablespaces;
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
        Connection connection = param.getConnection() == null ? Chat2DBContext.getConnection() : param.getConnection();
        return Chat2DBContext.getDbMetaData(param.getDbType()).tablespace(connection, param.getTablespaceName());
    }

    @Override
    public Sql createTablespace(DbTablespaceCreateRequest param) {
        Tablespace tablespace = Tablespace.builder()
                .name(param.getName())
                .dataFiles(List.of(param.getDataFile()))
                .fileBlockSize(param.getFileBlockSize())
                .build();
        String sql = Chat2DBContext.getSqlBuilder().ddl().tablespace().buildCreateTablespace(tablespace);
        return Sql.builder().sql(sql).build();
    }

    @Override
    public void modifyTablespace(DbTablespaceModifyRequest param) {
        Chat2DBContext.getDbManager().alterTablespaceRename(Chat2DBContext.getConnection(),
                param.getOldName(), param.getNewName());
        invalidateTablespaceCache(param.getDataSourceId());
    }

    @Override
    public TablespaceCapability capability(DbTablespaceQueryRequest param) {
        return TablespaceCapability.builder()
                .renameSupported(Chat2DBContext.getDbManager().supportsTablespaceRename())
                .build();
    }

    private List<Tablespace> getTablespaces(String dbType, Connection connection) {
        return Chat2DBContext.getDbMetaData(dbType).tablespaces(connection);
    }

    private void invalidateTablespaceCache(Long dataSourceId) {
        if (dataSourceId == null) {
            return;
        }
        CacheManage.fuzzyDelete(CacheKey.getTablespacesKey(dataSourceId));
    }
}

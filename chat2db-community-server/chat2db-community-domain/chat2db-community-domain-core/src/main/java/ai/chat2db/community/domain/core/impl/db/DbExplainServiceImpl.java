package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.result.DbExplainCapability;
import ai.chat2db.community.domain.api.model.result.DbExplainResult;
import ai.chat2db.community.domain.api.service.db.IDbExplainService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IExplainManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

@Service
public class DbExplainServiceImpl implements IDbExplainService {

    @Override
    public DbExplainResult explainJson(String sql, String requestId) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return manager().explainJson(Chat2DBContext.getConnection(), connectInfo, Chat2DBContext.getDbVersion(),
                sql, requestId);
    }

    @Override
    public DbExplainResult explainAnalyze(String sql, String requestId) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return manager().explainAnalyze(Chat2DBContext.getConnection(), connectInfo, Chat2DBContext.getDbVersion(),
                sql, requestId);
    }

    @Override
    public DbExplainCapability capability() {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        String databaseType = connectInfo == null ? null : connectInfo.getDbType();
        IExplainManager manager = Chat2DBContext.getExplainManager();
        if (manager == null) {
            return new DbExplainCapability(databaseType, null, false, false);
        }
        return manager.capability(databaseType, Chat2DBContext.getDbVersion());
    }

    @Override
    public boolean cancel(String requestId) {
        return manager().cancel(Chat2DBContext.getConnectInfo(), requestId);
    }

    private static IExplainManager manager() {
        IExplainManager manager = Chat2DBContext.getExplainManager();
        if (manager == null) {
            throw new BusinessException("sql.explain.unsupported");
        }
        return manager;
    }
}

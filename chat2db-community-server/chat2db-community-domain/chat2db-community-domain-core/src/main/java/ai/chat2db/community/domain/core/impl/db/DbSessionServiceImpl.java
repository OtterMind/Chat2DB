package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.DbSessionKillResult;
import ai.chat2db.community.domain.api.service.db.IDbSessionService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.ISessionManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DbSessionServiceImpl implements IDbSessionService {

    @Override
    public List<Map<String, Object>> list() {
        return manager().list(Chat2DBContext.getConnection(), Chat2DBContext.getDbVersion());
    }

    @Override
    public DbSessionKillResult kill(Long connectionId, String killType) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        String connectionUser = connectInfo == null ? null : connectInfo.getUser();
        return manager().kill(Chat2DBContext.getConnection(), Chat2DBContext.getDbVersion(), connectionUser,
                connectionId, killType);
    }

    private static ISessionManager manager() {
        ISessionManager manager = Chat2DBContext.getSessionManager();
        if (manager == null) {
            throw new BusinessException("mysql.session.unsupported");
        }
        return manager;
    }
}

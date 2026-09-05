package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.lock.LockView;
import ai.chat2db.community.domain.api.service.db.IDbLockService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.ILockManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;

/** Delegates lock inspection to the active database plugin. */
@Service
public class DbLockServiceImpl implements IDbLockService {

    @Override
    public LockView lockView(Long dataSourceId) {
        requireDatasourceContext(dataSourceId);
        ILockManager lockManager = Chat2DBContext.getLockManager();
        if (lockManager == null) {
            throw new BusinessException("lock.inspection.unsupported");
        }
        Connection connection = Chat2DBContext.getConnection();
        if (connection == null) {
            throw new BusinessException("connection error");
        }
        return lockManager.lockView(connection, dataSourceId);
    }

    private static void requireDatasourceContext(Long dataSourceId) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null || connectInfo.getDataSourceId() == null) {
            throw new BusinessException("datasource.context.required");
        }
        if (!connectInfo.getDataSourceId().equals(dataSourceId)) {
            throw new BusinessException("datasource.context.mismatch");
        }
    }
}

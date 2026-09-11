package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction;
import ai.chat2db.community.domain.api.service.db.IDbActiveTransactionService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IActiveTransactionManager;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DbActiveTransactionServiceImpl implements IDbActiveTransactionService {

    @Override
    public List<ActiveTransaction> activeTransactions() {
        IActiveTransactionManager manager = Chat2DBContext.getActiveTransactionManager();
        if (manager == null) {
            throw new BusinessException("activeTransaction.inspection.unsupported");
        }
        return manager.activeTransactions(Chat2DBContext.getConnection());
    }
}

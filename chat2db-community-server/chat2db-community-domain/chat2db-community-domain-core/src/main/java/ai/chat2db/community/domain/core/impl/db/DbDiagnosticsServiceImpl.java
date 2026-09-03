package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbStatusResponse;
import ai.chat2db.community.domain.api.service.db.IDbDiagnosticsService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDiagnosticsManager;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

@Service
public class DbDiagnosticsServiceImpl implements IDbDiagnosticsService {

    @Override
    public InnodbStatusResponse innodbStatus() {
        return manager().innodbStatus(Chat2DBContext.getConnection(), Chat2DBContext.getDbVersion());
    }

    private static IDiagnosticsManager manager() {
        IDiagnosticsManager manager = Chat2DBContext.getDiagnosticsManager();
        if (manager == null) {
            throw new BusinessException("mysql.diagnostics.unsupported");
        }
        return manager;
    }
}

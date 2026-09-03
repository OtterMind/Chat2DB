package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbVariableService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IVariableManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

@Service
public class DbVariableServiceImpl implements IDbVariableService {

    @Override
    public List<Map<String, Object>> variables(String scope, String kind) {
        return requireVariableManager().variables(requireConnection(), currentDbVersion(), scope, kind);
    }

    @Override
    public EditMeta editable(String variableName) {
        return requireVariableManager().editable(requireConnection(), currentDbVersion(), variableName);
    }

    @Override
    public String previewSetVariableSql(String variableName, String value, String scope) {
        return requireVariableManager().previewSetVariableSql(
                requireConnection(), currentDbVersion(), variableName, value, scope);
    }

    private IVariableManager requireVariableManager() {
        IVariableManager variableManager = Chat2DBContext.getVariableManager();
        if (variableManager == null) {
            throw new BusinessException("mysql.variables.unsupported");
        }
        return variableManager;
    }

    private Connection requireConnection() {
        Connection connection = Chat2DBContext.getConnection();
        if (connection == null) {
            throw new BusinessException("connection error");
        }
        return connection;
    }

    private String currentDbVersion() {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            return null;
        }
        String dbVersion = connectInfo.getDbVersion();
        return dbVersion == null || dbVersion.isBlank() ? Chat2DBContext.getDbVersion() : dbVersion;
    }
}

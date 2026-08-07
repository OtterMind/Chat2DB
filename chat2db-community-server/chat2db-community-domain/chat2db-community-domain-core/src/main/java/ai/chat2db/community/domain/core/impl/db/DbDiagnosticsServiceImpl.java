package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbDiagnosticsService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;

@Service
public class DbDiagnosticsServiceImpl implements IDbDiagnosticsService {

    private static final String SQL_SHOW_INNODB_STATUS = "SHOW ENGINE INNODB STATUS";
    private static final String FIELD_STATUS = "Status";
    private static final String ERROR_KEY_INNODB_STATUS_PERMISSION = "mysql.diagnostics.innodbStatusPermission";
    private static final int MYSQL_ERROR_ACCESS_DENIED = 1227;

    @Override
    public String innodbStatus() {
        Connection connection = Chat2DBContext.getConnection();
        try {
            return DefaultSQLExecutor.getInstance().execute(connection, SQL_SHOW_INNODB_STATUS, resultSet -> {
                if (resultSet.next()) {
                    return resultSet.getString(FIELD_STATUS);
                }
                return null;
            });
        } catch (RuntimeException e) {
            // SHOW ENGINE INNODB STATUS needs the PROCESS privilege; surface a clear message
            // instead of the raw driver exception.
            if (e.getCause() instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == MYSQL_ERROR_ACCESS_DENIED
                    || StringUtils.containsIgnoreCase(sqlException.getMessage(), "PROCESS privilege"))) {
                throw new BusinessException(ERROR_KEY_INNODB_STATUS_PERMISSION);
            }
            throw e;
        }
    }
}

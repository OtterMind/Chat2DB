package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbDiagnosticsService;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
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
        requireSupportedMysql();
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
            if (hasProcessPrivilegeError(e)) {
                throw new BusinessException(ERROR_KEY_INNODB_STATUS_PERMISSION);
            }
            throw e;
        }
    }

    static boolean hasProcessPrivilegeError(Throwable exception) {
        for (Throwable current = exception; current != null && current.getCause() != current; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == MYSQL_ERROR_ACCESS_DENIED
                    || StringUtils.containsIgnoreCase(sqlException.getMessage(), "PROCESS privilege"))) {
                return true;
            }
        }
        return false;
    }

    static boolean supportsInnodbStatus(String version) {
        if (StringUtils.isBlank(version)) {
            return false;
        }
        String[] parts = version.replaceFirst("^[^0-9]*", "").split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 5 || major == 5 && minor >= 7;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void requireSupportedMysql() {
        String dbType = Chat2DBContext.getConnectInfo() == null ? null : Chat2DBContext.getConnectInfo().getDbType();
        if (!DatabaseTypeEnum.MYSQL.name().equalsIgnoreCase(dbType)
                || !supportsInnodbStatus(Chat2DBContext.getDbVersion())) {
            throw new BusinessException("mysql.diagnostics.unsupported");
        }
    }
}

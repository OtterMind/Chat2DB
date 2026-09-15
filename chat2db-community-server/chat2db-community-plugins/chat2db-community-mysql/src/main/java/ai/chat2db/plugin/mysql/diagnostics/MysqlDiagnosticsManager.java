package ai.chat2db.plugin.mysql.diagnostics;

import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbStatusResponse;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IDiagnosticsManager;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;

public class MysqlDiagnosticsManager implements IDiagnosticsManager {

    private static final String SQL_SHOW_INNODB_STATUS = "SHOW ENGINE INNODB STATUS";
    private static final String FIELD_STATUS = "Status";
    private static final String ERROR_KEY_INNODB_STATUS_PERMISSION = "mysql.diagnostics.innodbStatusPermission";
    private static final int MYSQL_ERROR_ACCESS_DENIED = 1227;

    @Override
    public InnodbStatusResponse innodbStatus(Connection connection, String databaseVersion) {
        requireSupportedMysql(databaseVersion);
        try {
            String rawText = DefaultSQLExecutor.getInstance().execute(connection, SQL_SHOW_INNODB_STATUS, resultSet -> {
                if (resultSet.next()) {
                    return resultSet.getString(FIELD_STATUS);
                }
                return null;
            });
            return parseInnodbStatus(rawText);
        } catch (RuntimeException exception) {
            if (hasProcessPrivilegeError(exception)) {
                throw new BusinessException(ERROR_KEY_INNODB_STATUS_PERMISSION);
            }
            throw exception;
        }
    }

    static InnodbStatusResponse parseInnodbStatus(String rawText) {
        return InnodbStatusParser.parse(rawText);
    }

    static boolean hasProcessPrivilegeError(Throwable exception) {
        for (Throwable current = exception; current != null && current.getCause() != current;
             current = current.getCause()) {
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

    private static void requireSupportedMysql(String databaseVersion) {
        if (!supportsInnodbStatus(databaseVersion)) {
            throw new BusinessException("mysql.diagnostics.unsupported");
        }
    }
}

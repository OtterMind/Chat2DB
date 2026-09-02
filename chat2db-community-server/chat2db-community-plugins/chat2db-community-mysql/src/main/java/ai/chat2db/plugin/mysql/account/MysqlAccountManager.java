package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.domain.api.enums.plugin.PasswordExpirePolicyEnum;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.community.domain.api.model.account.AccountExecuteResponse;
import ai.chat2db.community.domain.api.model.account.AccountInfo;
import ai.chat2db.community.domain.api.model.account.AccountManagerCapability;
import ai.chat2db.community.domain.api.model.account.AccountPreview;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IAccountManager;
import ai.chat2db.plugin.mysql.enums.account.MysqlPrivilege;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static ai.chat2db.plugin.mysql.constant.MysqlAccountManageConstants.*;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SELECT_ACCOUNT_LOCKED_MYSQL_USER;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SELECT_USER_HOST_MYSQL_USER;

public class MysqlAccountManager implements IAccountManager {
    @Override
    public AccountManagerCapability capability(Connection connection) {
        AccountManagerCapability capability = new AccountManagerCapability();
        capability.setEditablePrivileges(MysqlPrivilege.names());
        capability.setAccountListReadable(canReadMysqlUser(connection));
        capability.setAccountLockSupported(canReadAccountLocked(connection));
        capability.setPasswordExpirationSupported(canReadPasswordExpiration(connection));
        capability.setResourceLimitsSupported(canReadResourceLimits(connection));
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            capability.setProductName(metaData.getDatabaseProductName());
            capability.setProductVersion(metaData.getDatabaseProductVersion());
            capability.setRoleManagementSupported(supportsRoles(metaData));
        } catch (SQLException e) {
            capability.setMessage(e.getMessage());
            capability.setRoleManagementSupported(Boolean.FALSE);
        }
        capability.setCurrentUser(querySingleString(connection, SQL_SELECT_CURRENT_USER));
        return capability;
    }

    @Override
    public List<AccountInfo> listAccounts(Connection connection) {
        try {
            return queryAccounts(connection, AccountQueryMode.SETTINGS);
        } catch (SQLException settingsColumnError) {
            try {
                return queryAccounts(connection, AccountQueryMode.LOCKED);
            } catch (SQLException lockedColumnError) {
                try {
                    return queryAccounts(connection, AccountQueryMode.BASIC);
                } catch (SQLException e) {
                    throw new BusinessException(ERROR_KEY_ACCOUNT_LIST_UNAVAILABLE, null, e);
                }
            }
        }
    }

    @Override
    public List<String> showGrants(Connection connection, String user, String host) {
        try {
            return queryGrants(connection, user, host);
        } catch (SQLException e) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_GRANTS_UNAVAILABLE, null, e);
        }
    }

    @Override
    public AccountPreview preview(Connection connection, AccountOperationRequest command) {
        validateRoleCapability(connection, command);
        String sql = MysqlAccountSqlBuilder.buildSql(command);
        AccountPreview preview = new AccountPreview();
        preview.setActionType(command.getActionType());
        preview.setSql(MysqlAccountSqlBuilder.buildDisplaySql(command));
        preview.setPreviewToken(MysqlAccountSqlBuilder.previewToken(sql));
        return preview;
    }

    @Override
    public AccountExecuteResponse execute(Connection connection, AccountOperationRequest command) {
        AccountPreview preview = preview(connection, command);
        if (!StringUtils.equals(preview.getPreviewToken(), command.getPreviewToken())) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_PREVIEW_TOKEN_MISMATCH);
        }

        AccountExecuteResponse result = new AccountExecuteResponse();
        result.setActionType(preview.getActionType());
        result.setSql(preview.getSql());
        String executionSql = MysqlAccountSqlBuilder.buildSql(command);

        try (PreparedStatement statement = connection.prepareStatement(executionSql)) {
            statement.execute();
            result.setSuccess(Boolean.TRUE);
            result.setMessage(MESSAGE_OK);
        } catch (SQLException e) {
            result.setSuccess(Boolean.FALSE);
            result.setMessage(e.getMessage());
            result.setFailureCode(ERROR_KEY_ACCOUNT_EXECUTE_FAILED);
            result.setErrorCode(e.getErrorCode());
            result.setSqlState(e.getSQLState());
        }
        return result;
    }

    private void validateRoleCapability(Connection connection, AccountOperationRequest command) {
        if (!command.getActionType().contains("ROLE")) {
            return;
        }
        try {
            if (!supportsRoles(connection.getMetaData())) {
                throw new BusinessException(ERROR_KEY_ACCOUNT_ROLE_UNSUPPORTED);
            }
        } catch (SQLException e) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_ROLE_UNSUPPORTED, null, e);
        }
    }

    private boolean supportsRoles(DatabaseMetaData metadata) throws SQLException {
        String productName = StringUtils.defaultString(metadata.getDatabaseProductName()).toLowerCase(java.util.Locale.ROOT);
        return !productName.contains("mariadb") && metadata.getDatabaseMajorVersion() >= 8;
    }

    private List<AccountInfo> queryAccounts(Connection connection, AccountQueryMode queryMode) throws SQLException {
        List<AccountInfo> accounts = new ArrayList<>();
        String sql = switch (queryMode) {
            case SETTINGS -> SQL_SELECT_MYSQL_USERS_WITH_SETTINGS;
            case LOCKED -> SQL_SELECT_MYSQL_USERS_WITH_LOCK;
            case BASIC -> SQL_SELECT_MYSQL_USERS;
        };
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                AccountInfo account = new AccountInfo();
                account.setUser(resultSet.getString(FIELD_USER));
                account.setHost(resultSet.getString(FIELD_HOST));
                account.setAuthenticationPlugin(safeGetString(resultSet, FIELD_PLUGIN));
                if (queryMode != AccountQueryMode.BASIC) {
                    String accountLocked = safeGetString(resultSet, FIELD_ACCOUNT_LOCKED);
                    account.setLocked(StringUtils.isBlank(accountLocked) ? null : VALUE_ACCOUNT_LOCKED_YES.equalsIgnoreCase(accountLocked));
                }
                if (queryMode == AccountQueryMode.SETTINGS) {
                    String passwordExpired = safeGetString(resultSet, FIELD_PASSWORD_EXPIRED);
                    account.setPasswordExpired(StringUtils.isBlank(passwordExpired) ? null
                            : VALUE_ACCOUNT_LOCKED_YES.equalsIgnoreCase(passwordExpired));
                    account.setPasswordLastChanged(safeGetString(resultSet, FIELD_PASSWORD_LAST_CHANGED));
                    account.setPasswordLifetime(safeGetInteger(resultSet, FIELD_PASSWORD_LIFETIME));
                    account.setPasswordExpirePolicy(passwordExpirePolicy(account.getPasswordExpired(),
                            account.getPasswordLifetime()));
                    account.setMaxQueriesPerHour(safeGetInteger(resultSet, FIELD_MAX_QUESTIONS));
                    account.setMaxUpdatesPerHour(safeGetInteger(resultSet, FIELD_MAX_UPDATES));
                    account.setMaxConnectionsPerHour(safeGetInteger(resultSet, FIELD_MAX_CONNECTIONS));
                    account.setMaxUserConnections(safeGetInteger(resultSet, FIELD_MAX_USER_CONNECTIONS));
                }
                account.setDisplayName(account.getUser() + ACCOUNT_DISPLAY_NAME_SEPARATOR + account.getHost());
                accounts.add(account);
            }
            return accounts;
        }
    }

    private List<String> queryGrants(Connection connection, String user, String host) throws SQLException {
        List<String> grants = new ArrayList<>();
        String sql = MysqlAccountSqlBuilder.showGrantsSql(user, host);
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                grants.add(resultSet.getString(1));
            }
            return grants;
        }
    }

    private boolean canReadMysqlUser(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_USER_HOST_MYSQL_USER);
             ResultSet ignored = statement.executeQuery()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean canReadAccountLocked(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_ACCOUNT_LOCKED_MYSQL_USER);
             ResultSet ignored = statement.executeQuery()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean canReadPasswordExpiration(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_PASSWORD_EXPIRATION_MYSQL_USER);
             ResultSet ignored = statement.executeQuery()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean canReadResourceLimits(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_RESOURCE_LIMITS_MYSQL_USER);
             ResultSet ignored = statement.executeQuery()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private String querySingleString(Connection connection, String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    private String safeGetString(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    private Integer safeGetInteger(ResultSet resultSet, String column) {
        try {
            int value = resultSet.getInt(column);
            return resultSet.wasNull() ? null : value;
        } catch (SQLException e) {
            return null;
        }
    }

    private String passwordExpirePolicy(Boolean passwordExpired, Integer passwordLifetime) {
        if (Boolean.TRUE.equals(passwordExpired)) {
            return PasswordExpirePolicyEnum.IMMEDIATE.name();
        }
        if (passwordLifetime == null) {
            return PasswordExpirePolicyEnum.DEFAULT.name();
        }
        if (passwordLifetime == 0) {
            return PasswordExpirePolicyEnum.NEVER.name();
        }
        return PasswordExpirePolicyEnum.INTERVAL.name();
    }

    private enum AccountQueryMode {
        SETTINGS,
        LOCKED,
        BASIC
    }
}

package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IAccountManager;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.community.domain.api.model.account.AccountExecuteResponse;
import ai.chat2db.community.domain.api.model.account.AccountInfo;
import ai.chat2db.community.domain.api.model.account.AccountManagerCapability;
import ai.chat2db.community.domain.api.model.account.AccountPreview;
import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.plugin.mysql.enums.account.MysqlPrivilege;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static ai.chat2db.plugin.mysql.constant.MysqlAccountManageConstants.*;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SELECT_ACCOUNT_LOCKED_MYSQL_USER;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SELECT_USER_HOST_MYSQL_USER;

public class MysqlAccountManager implements IAccountManager {

    private final MysqlAccountPreviewToken previewToken = MysqlAccountPreviewToken.INSTANCE;

    @Override
    public AccountManagerCapability capability(Connection connection) {
        AccountManagerCapability capability = new AccountManagerCapability();
        capability.setEditablePrivileges(MysqlPrivilege.names());
        capability.setAccountListReadable(canReadMysqlUser(connection));
        capability.setAccountLockSupported(canReadAccountLocked(connection));
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            capability.setProductName(metaData.getDatabaseProductName());
            capability.setProductVersion(metaData.getDatabaseProductVersion());
            boolean supported = supportsSecurityManagement(metaData);
            capability.setAuthPluginManagementSupported(supported);
            capability.setTlsRequirementManagementSupported(supported);
            capability.setAuthenticationPlugins(supported ? queryAuthenticationPlugins(connection) : Collections.emptyList());
        } catch (SQLException e) {
            capability.setMessage(e.getMessage());
            capability.setAuthPluginManagementSupported(Boolean.FALSE);
            capability.setTlsRequirementManagementSupported(Boolean.FALSE);
            capability.setAuthenticationPlugins(Collections.emptyList());
        }
        capability.setCurrentUser(querySingleString(connection, SQL_SELECT_CURRENT_USER));
        return capability;
    }

    @Override
    public List<AccountInfo> listAccounts(Connection connection) {
        try {
            return queryAccounts(connection, true);
        } catch (SQLException lockedColumnError) {
            try {
                return queryAccounts(connection, false);
            } catch (SQLException e) {
                throw new BusinessException(ERROR_KEY_ACCOUNT_LIST_UNAVAILABLE, null, e);
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
        validateSecurityCommand(connection, command);
        String sql = MysqlAccountSqlBuilder.buildSql(command);
        AccountPreview preview = new AccountPreview();
        preview.setActionType(command.getActionType());
        preview.setSql(MysqlAccountSqlBuilder.buildDisplaySql(command));
        preview.setPreviewToken(previewToken.issue(sql, currentDataSourceId()));
        return preview;
    }

    @Override
    public AccountExecuteResponse execute(Connection connection, AccountOperationRequest command) {
        validateSecurityCommand(connection, command);
        String executionSql = MysqlAccountSqlBuilder.buildSql(command);
        if (!previewToken.verify(command.getPreviewToken(), executionSql, currentDataSourceId())) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_PREVIEW_TOKEN_MISMATCH);
        }

        AccountExecuteResponse result = new AccountExecuteResponse();
        result.setActionType(command.getActionType());
        result.setSql(MysqlAccountSqlBuilder.buildDisplaySql(command));

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

    private void validateSecurityCommand(Connection connection, AccountOperationRequest command) {
        AccountActionTypeEnum actionType = AccountActionTypeEnum.from(command == null ? null : command.getActionType());
        if (actionType != AccountActionTypeEnum.ALTER_AUTH_PLUGIN) {
            return;
        }
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            if (!supportsSecurityManagement(metadata)) {
                throw new BusinessException(ERROR_KEY_ACCOUNT_SECURITY_MANAGEMENT_UNSUPPORTED);
            }
            if (StringUtils.isNotBlank(command.getAuthPlugin())
                    && !queryAuthenticationPlugins(connection).contains(command.getAuthPlugin())) {
                throw new BusinessException(ERROR_KEY_ACCOUNT_AUTH_PLUGIN_UNSUPPORTED);
            }
        } catch (SQLException e) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_AUTH_PLUGIN_UNAVAILABLE, null, e);
        }
    }

    private Long currentDataSourceId() {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return connectInfo == null ? null : connectInfo.getDataSourceId();
    }

    private boolean supportsSecurityManagement(DatabaseMetaData metadata) throws SQLException {
        String productName = StringUtils.defaultString(metadata.getDatabaseProductName()).toLowerCase(java.util.Locale.ROOT);
        int major = metadata.getDatabaseMajorVersion();
        int minor = metadata.getDatabaseMinorVersion();
        if (productName.contains("mariadb")) {
            return major > 10 || (major == 10 && minor >= 2);
        }
        return major > 5 || (major == 5 && minor >= 7);
    }

    private List<String> queryAuthenticationPlugins(Connection connection) throws SQLException {
        List<String> plugins = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_ACTIVE_AUTHENTICATION_PLUGINS);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                plugins.add(resultSet.getString(1));
            }
        }
        return plugins;
    }

    private List<AccountInfo> queryAccounts(Connection connection, boolean includeLocked) throws SQLException {
        List<AccountInfo> accounts = new ArrayList<>();
        String sql = includeLocked ? SQL_SELECT_MYSQL_USERS_WITH_LOCK : SQL_SELECT_MYSQL_USERS;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                AccountInfo account = new AccountInfo();
                account.setUser(resultSet.getString(FIELD_USER));
                account.setHost(resultSet.getString(FIELD_HOST));
                account.setAuthenticationPlugin(safeGetString(resultSet, FIELD_PLUGIN));
                account.setTlsRequirement(normalizeTlsRequirement(safeGetString(resultSet, FIELD_SSL_TYPE)));
                account.setTlsCipher(safeGetString(resultSet, FIELD_SSL_CIPHER));
                account.setTlsIssuer(safeGetString(resultSet, FIELD_X509_ISSUER));
                account.setTlsSubject(safeGetString(resultSet, FIELD_X509_SUBJECT));
                if (includeLocked) {
                    String accountLocked = safeGetString(resultSet, FIELD_ACCOUNT_LOCKED);
                    account.setLocked(StringUtils.isBlank(accountLocked) ? null : VALUE_ACCOUNT_LOCKED_YES.equalsIgnoreCase(accountLocked));
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

    private String normalizeTlsRequirement(String sslType) {
        if (StringUtils.isBlank(sslType)) {
            return VALUE_TLS_REQUIREMENT_NONE;
        }
        if (VALUE_MYSQL_SSL_TYPE_ANY.equalsIgnoreCase(sslType)) {
            return VALUE_TLS_REQUIREMENT_SSL;
        }
        return sslType.toUpperCase(java.util.Locale.ROOT);
    }

    private String safeGetString(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }
}

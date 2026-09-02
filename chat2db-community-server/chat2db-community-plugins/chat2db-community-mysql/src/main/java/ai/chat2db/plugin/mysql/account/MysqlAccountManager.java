package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IAccountManager;
import ai.chat2db.community.domain.api.model.account.AccountDefinerImpact;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.community.domain.api.model.account.AccountExecuteResponse;
import ai.chat2db.community.domain.api.model.account.AccountInfo;
import ai.chat2db.community.domain.api.model.account.AccountManagerCapability;
import ai.chat2db.community.domain.api.model.account.AccountPreview;
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
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            capability.setProductName(metaData.getDatabaseProductName());
            capability.setProductVersion(metaData.getDatabaseProductVersion());
        } catch (SQLException e) {
            capability.setMessage(e.getMessage());
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
        AccountPreview preview = buildPreview(command);
        if (!isRename(command)) {
            return preview;
        }
        preview.setOldAccountSql(MysqlAccountSqlBuilder.account(command));
        preview.setNewAccountSql(MysqlAccountSqlBuilder.account(command.getNewUser(), command.getNewHost()));
        preview.setWarningCodes(new ArrayList<>(List.of(WARNING_KEY_ACCOUNT_RENAME_IMPACT)));
        if (Boolean.TRUE.equals(accountExists(connection, command.getNewUser(), command.getNewHost()))) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_RENAME_TARGET_EXISTS);
        }
        populateDefinerImpacts(connection, command, preview);
        return preview;
    }

    private AccountPreview buildPreview(AccountOperationRequest command) {
        String sql = MysqlAccountSqlBuilder.buildSql(command);
        AccountPreview preview = new AccountPreview();
        preview.setActionType(command.getActionType());
        preview.setSql(MysqlAccountSqlBuilder.buildDisplaySql(command));
        preview.setPreviewToken(MysqlAccountSqlBuilder.previewToken(sql));
        return preview;
    }

    @Override
    public AccountExecuteResponse execute(Connection connection, AccountOperationRequest command) {
        AccountPreview preview = buildPreview(command);
        if (!StringUtils.equals(preview.getPreviewToken(), command.getPreviewToken())) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_PREVIEW_TOKEN_MISMATCH);
        }

        AccountExecuteResponse result = new AccountExecuteResponse();
        result.setActionType(preview.getActionType());
        result.setSql(preview.getSql());
        String executionSql = MysqlAccountSqlBuilder.buildSql(command);

        if (isRename(command) && Boolean.TRUE.equals(accountExists(connection, command.getNewUser(), command.getNewHost()))) {
            result.setSuccess(Boolean.FALSE);
            result.setMessage(ERROR_KEY_ACCOUNT_RENAME_TARGET_EXISTS);
            result.setFailureCode(ERROR_KEY_ACCOUNT_RENAME_TARGET_EXISTS);
            return result;
        }

        try (PreparedStatement statement = connection.prepareStatement(executionSql)) {
            statement.execute();
            result.setSuccess(Boolean.TRUE);
            result.setMessage(MESSAGE_OK);
            verifyRenameReadback(connection, command, result);
        } catch (SQLException e) {
            result.setSuccess(Boolean.FALSE);
            result.setMessage(e.getMessage());
            result.setFailureCode(ERROR_KEY_ACCOUNT_EXECUTE_FAILED);
            result.setErrorCode(e.getErrorCode());
            result.setSqlState(e.getSQLState());
        }
        return result;
    }

    private void populateDefinerImpacts(Connection connection, AccountOperationRequest command, AccountPreview preview) {
        List<AccountDefinerImpact> impacts = new ArrayList<>();
        boolean complete = true;
        String definer = command.getUser() + ACCOUNT_DISPLAY_NAME_SEPARATOR + command.getHost();
        for (DefinerQuery query : definerQueries()) {
            try {
                impacts.addAll(queryDefinerImpacts(connection, query, definer));
            } catch (SQLException e) {
                complete = false;
            }
        }
        preview.setDefinerImpacts(impacts);
        preview.setDefinerEnumerationComplete(complete);
        if (!complete) {
            preview.getWarningCodes().add(WARNING_KEY_ACCOUNT_DEFINER_ENUMERATION_INCOMPLETE);
        }
    }

    private List<AccountDefinerImpact> queryDefinerImpacts(Connection connection, DefinerQuery query, String definer)
            throws SQLException {
        List<AccountDefinerImpact> impacts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            statement.setString(1, query.objectType());
            statement.setString(2, definer);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    AccountDefinerImpact impact = new AccountDefinerImpact();
                    impact.setObjectType(resultSet.getString(FIELD_OBJECT_TYPE));
                    impact.setSchemaName(resultSet.getString(FIELD_OBJECT_SCHEMA));
                    impact.setObjectName(resultSet.getString(FIELD_OBJECT_NAME));
                    impact.setDefiner(resultSet.getString(FIELD_DEFINER));
                    impacts.add(impact);
                }
            }
        }
        return impacts;
    }

    private List<DefinerQuery> definerQueries() {
        return List.of(
                new DefinerQuery("VIEW",
                        "SELECT ? AS OBJECT_TYPE, TABLE_SCHEMA AS OBJECT_SCHEMA, TABLE_NAME AS OBJECT_NAME, DEFINER "
                                + "FROM information_schema.VIEWS WHERE DEFINER = ? ORDER BY TABLE_SCHEMA, TABLE_NAME"),
                new DefinerQuery("FUNCTION",
                        "SELECT ? AS OBJECT_TYPE, ROUTINE_SCHEMA AS OBJECT_SCHEMA, ROUTINE_NAME AS OBJECT_NAME, DEFINER "
                                + "FROM information_schema.ROUTINES WHERE ROUTINE_TYPE = 'FUNCTION' AND DEFINER = ? "
                                + "ORDER BY ROUTINE_SCHEMA, ROUTINE_NAME"),
                new DefinerQuery("PROCEDURE",
                        "SELECT ? AS OBJECT_TYPE, ROUTINE_SCHEMA AS OBJECT_SCHEMA, ROUTINE_NAME AS OBJECT_NAME, DEFINER "
                                + "FROM information_schema.ROUTINES WHERE ROUTINE_TYPE = 'PROCEDURE' AND DEFINER = ? "
                                + "ORDER BY ROUTINE_SCHEMA, ROUTINE_NAME"),
                new DefinerQuery("TRIGGER",
                        "SELECT ? AS OBJECT_TYPE, TRIGGER_SCHEMA AS OBJECT_SCHEMA, TRIGGER_NAME AS OBJECT_NAME, DEFINER "
                                + "FROM information_schema.TRIGGERS WHERE DEFINER = ? ORDER BY TRIGGER_SCHEMA, TRIGGER_NAME"),
                new DefinerQuery("EVENT",
                        "SELECT ? AS OBJECT_TYPE, EVENT_SCHEMA AS OBJECT_SCHEMA, EVENT_NAME AS OBJECT_NAME, DEFINER "
                                + "FROM information_schema.EVENTS WHERE DEFINER = ? ORDER BY EVENT_SCHEMA, EVENT_NAME")
        );
    }

    private void verifyRenameReadback(Connection connection, AccountOperationRequest command, AccountExecuteResponse result) {
        if (!isRename(command) || !Boolean.TRUE.equals(result.getSuccess())) {
            return;
        }
        Boolean sourceExists = accountExists(connection, command.getUser(), command.getHost());
        Boolean targetExists = accountExists(connection, command.getNewUser(), command.getNewHost());
        if (sourceExists == null || targetExists == null) {
            return;
        }
        if (Boolean.TRUE.equals(sourceExists) || Boolean.FALSE.equals(targetExists)) {
            result.setSuccess(Boolean.FALSE);
            result.setMessage(ERROR_KEY_ACCOUNT_RENAME_READBACK_FAILED);
            result.setFailureCode(ERROR_KEY_ACCOUNT_RENAME_READBACK_FAILED);
        }
    }

    private boolean isRename(AccountOperationRequest command) {
        return "RENAME_USER".equals(command.getActionType());
    }

    private Boolean accountExists(Connection connection, String user, String host) {
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_MYSQL_USER_BY_ACCOUNT)) {
            statement.setString(1, user);
            statement.setString(2, host);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException ignored) {
            // A user with RENAME USER may not have mysql.user read access. MySQL remains the authority in that case.
            return null;
        }
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

    private String safeGetString(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    private record DefinerQuery(String objectType, String sql) {
    }
}

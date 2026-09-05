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
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            boolean rolesSupported = supportsRoles(metaData);
            capability.setRoleManagementSupported(rolesSupported);
            capability.setActiveRoles(rolesSupported ? parseRoleAccounts(querySingleString(connection, SQL_SELECT_CURRENT_ROLE)) : List.of());
        } catch (SQLException e) {
            capability.setMessage(e.getMessage());
            capability.setRoleManagementSupported(Boolean.FALSE);
            capability.setActiveRoles(List.of());
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
        AccountActionTypeEnum actionType = AccountActionTypeEnum.from(command == null ? null : command.getActionType());
        boolean roleAction = switch (actionType) {
            case CREATE_ROLE, DROP_ROLE, GRANT_ROLE, REVOKE_ROLE, SET_DEFAULT_ROLE -> true;
            default -> false;
        };
        if (!roleAction) {
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

    private List<AccountInfo> queryAccounts(Connection connection, boolean includeLocked) throws SQLException {
        List<AccountInfo> accounts = new ArrayList<>();
        Set<String> roleAccountKeys = queryRoleAccountKeys(connection);
        List<RoleEdge> roleEdges = queryRoleEdges(connection);
        Map<String, List<RoleEdge>> roleEdgesByGrantee = groupRoleEdgesByGrantee(roleEdges);
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
                account.setRole(roleAccountKeys.contains(accountKey(account.getUser(), account.getHost()))
                        || isStandaloneRole(resultSet, account));
                List<AccountInfo> directRoles = directRoles(roleEdgesByGrantee, account.getUser(), account.getHost());
                account.setDirectRoles(directRoles);
                List<AccountInfo> inheritedRoles = inheritedRoles(roleEdgesByGrantee, directRoles);
                account.setInheritedRoles(inheritedRoles);
                account.setEffectiveRoles(effectiveRoles(directRoles, inheritedRoles));
                account.setDefaultRoles(queryDefaultRoles(connection, account.getUser(), account.getHost()));
                accounts.add(account);
            }
            return accounts;
        }
    }

    private List<AccountInfo> queryDefaultRoles(Connection connection, String user, String host) {
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_DEFAULT_ROLES)) {
            statement.setString(1, user);
            statement.setString(2, host);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AccountInfo> roles = new ArrayList<>();
                while (resultSet.next()) {
                    roles.add(roleAccount(resultSet.getString(1), resultSet.getString(2)));
                }
                return roles;
            }
        } catch (SQLException e) {
            return List.of();
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

    private Set<String> queryRoleAccountKeys(Connection connection) {
        Set<String> roles = new HashSet<>();
        queryRoleAccountKeys(connection, SQL_SELECT_ROLE_ACCOUNTS, roles);
        queryRoleAccountKeys(connection, SQL_SELECT_DEFAULT_ROLE_ACCOUNTS, roles);
        return roles;
    }

    private void queryRoleAccountKeys(Connection connection, String sql, Set<String> roles) {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                roles.add(accountKey(safeGetString(resultSet, FIELD_ROLE_USER), safeGetString(resultSet, FIELD_ROLE_HOST)));
            }
        } catch (SQLException e) {
            return;
        }
    }

    private List<RoleEdge> queryRoleEdges(Connection connection) {
        List<RoleEdge> edges = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_ROLE_EDGES);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                edges.add(new RoleEdge(
                        safeGetString(resultSet, FIELD_ROLE_USER),
                        safeGetString(resultSet, FIELD_ROLE_HOST),
                        safeGetString(resultSet, FIELD_GRANTEE_USER),
                        safeGetString(resultSet, FIELD_GRANTEE_HOST),
                        parseBoolean(safeGetString(resultSet, FIELD_ADMIN_OPTION))));
            }
        } catch (SQLException e) {
            return List.of();
        }
        return edges;
    }

    private Map<String, List<RoleEdge>> groupRoleEdgesByGrantee(List<RoleEdge> roleEdges) {
        Map<String, List<RoleEdge>> result = new LinkedHashMap<>();
        for (RoleEdge edge : roleEdges) {
            result.computeIfAbsent(edge.granteeKey(), ignored -> new ArrayList<>()).add(edge);
        }
        return result;
    }

    private List<AccountInfo> directRoles(Map<String, List<RoleEdge>> roleEdgesByGrantee, String user, String host) {
        List<RoleEdge> edges = roleEdgesByGrantee.get(accountKey(user, host));
        if (edges == null || edges.isEmpty()) {
            return List.of();
        }
        List<AccountInfo> roles = new ArrayList<>();
        for (RoleEdge edge : edges) {
            roles.add(roleAccount(edge.roleUser(), edge.roleHost(), edge.adminOption()));
        }
        return roles;
    }

    private List<AccountInfo> inheritedRoles(Map<String, List<RoleEdge>> roleEdgesByGrantee, List<AccountInfo> directRoles) {
        if (directRoles == null || directRoles.isEmpty()) {
            return List.of();
        }

        Set<String> directRoleKeys = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        for (AccountInfo directRole : directRoles) {
            String key = accountKey(directRole.getUser(), directRole.getHost());
            if (directRoleKeys.add(key)) {
                pending.add(key);
            }
        }

        Set<String> visited = new HashSet<>(directRoleKeys);
        Map<String, AccountInfo> inherited = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            String granteeKey = pending.removeFirst();
            List<RoleEdge> edges = roleEdgesByGrantee.get(granteeKey);
            if (edges == null) {
                continue;
            }
            for (RoleEdge edge : edges) {
                String roleKey = edge.roleKey();
                if (directRoleKeys.contains(roleKey)) {
                    visited.add(roleKey);
                    continue;
                }
                if (visited.add(roleKey)) {
                    inherited.put(roleKey, roleAccount(edge.roleUser(), edge.roleHost(), edge.adminOption()));
                    pending.add(roleKey);
                }
            }
        }
        return List.copyOf(inherited.values());
    }

    private List<AccountInfo> effectiveRoles(List<AccountInfo> directRoles, List<AccountInfo> inheritedRoles) {
        Map<String, AccountInfo> roles = new LinkedHashMap<>();
        for (AccountInfo role : directRoles) {
            roles.put(accountKey(role.getUser(), role.getHost()), role);
        }
        for (AccountInfo role : inheritedRoles) {
            roles.putIfAbsent(accountKey(role.getUser(), role.getHost()), role);
        }
        return List.copyOf(roles.values());
    }

    private List<AccountInfo> parseRoleAccounts(String value) {
        if (StringUtils.isBlank(value) || StringUtils.equalsIgnoreCase(value.trim(), "NONE")) {
            return List.of();
        }
        List<AccountInfo> roles = new ArrayList<>();
        for (String item : splitAccountList(value)) {
            AccountInfo role = parseRoleAccount(item.trim());
            if (role != null) {
                roles.add(role);
            }
        }
        return roles;
    }

    private AccountInfo parseRoleAccount(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        int at = accountSeparator(value);
        if (at <= 0 || at >= value.length() - 1) {
            return roleAccount(unquoteAccountPart(value), "%");
        }
        return roleAccount(unquoteAccountPart(value.substring(0, at)), unquoteAccountPart(value.substring(at + 1)));
    }

    private List<String> splitAccountList(String value) {
        List<String> accounts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                current.append(character);
                if (character == quote) {
                    if (index + 1 < value.length() && value.charAt(index + 1) == quote) {
                        current.append(value.charAt(++index));
                    } else {
                        quote = 0;
                    }
                }
            } else if (character == '`' || character == '\'') {
                quote = character;
                current.append(character);
            } else if (character == ',') {
                accounts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        accounts.add(current.toString());
        return accounts;
    }

    private int accountSeparator(String value) {
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                if (character == quote) {
                    if (index + 1 < value.length() && value.charAt(index + 1) == quote) {
                        index++;
                    } else {
                        quote = 0;
                    }
                }
            } else if (character == '`' || character == '\'') {
                quote = character;
            } else if (character == '@') {
                return index;
            }
        }
        return -1;
    }

    private boolean isStandaloneRole(ResultSet resultSet, AccountInfo account) {
        if (!Boolean.TRUE.equals(account.getLocked())) {
            return false;
        }
        String authenticationString = safeGetString(resultSet, FIELD_AUTHENTICATION_STRING);
        String passwordExpired = safeGetString(resultSet, FIELD_PASSWORD_EXPIRED);
        return StringUtils.EMPTY.equals(authenticationString)
                && VALUE_ACCOUNT_LOCKED_YES.equalsIgnoreCase(passwordExpired);
    }

    private String unquoteAccountPart(String value) {
        String trimmed = StringUtils.trimToEmpty(value);
        if (trimmed.length() >= 2) {
            char quote = trimmed.charAt(0);
            if ((quote == '\'' || quote == '`') && trimmed.charAt(trimmed.length() - 1) == quote) {
                String unquoted = trimmed.substring(1, trimmed.length() - 1);
                String escapedQuote = String.valueOf(quote) + quote;
                return unquoted.replace(escapedQuote, String.valueOf(quote)).replace("\\\\", "\\");
            }
        }
        return trimmed;
    }

    private AccountInfo roleAccount(String user, String host) {
        return roleAccount(user, host, null);
    }

    private AccountInfo roleAccount(String user, String host, Boolean adminOption) {
        AccountInfo role = new AccountInfo();
        role.setUser(user);
        role.setHost(host);
        role.setDisplayName(user + ACCOUNT_DISPLAY_NAME_SEPARATOR + host);
        role.setRole(Boolean.TRUE);
        role.setAdminOption(adminOption);
        role.setDirectRoles(List.of());
        role.setInheritedRoles(List.of());
        role.setEffectiveRoles(List.of());
        role.setDefaultRoles(List.of());
        return role;
    }

    private String accountKey(String user, String host) {
        return StringUtils.defaultString(user) + "\0" + StringUtils.defaultString(host);
    }

    private String safeGetString(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    private boolean parseBoolean(String value) {
        return StringUtils.equalsAnyIgnoreCase(StringUtils.trimToEmpty(value), "Y", "YES", "1", "TRUE");
    }

    private record RoleEdge(String roleUser, String roleHost, String granteeUser, String granteeHost,
                            Boolean adminOption) {

        private String roleKey() {
            return StringUtils.defaultString(roleUser) + "\0" + StringUtils.defaultString(roleHost);
        }

        private String granteeKey() {
            return StringUtils.defaultString(granteeUser) + "\0" + StringUtils.defaultString(granteeHost);
        }
    }
}

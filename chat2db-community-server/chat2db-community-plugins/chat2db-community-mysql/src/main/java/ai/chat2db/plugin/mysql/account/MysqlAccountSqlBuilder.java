package ai.chat2db.plugin.mysql.account;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.PrivilegeScopeEnum;
import ai.chat2db.community.domain.api.enums.plugin.DefaultRoleModeEnum;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.plugin.mysql.enums.account.MysqlPrivilege;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.ALL_DATABASE_ALL_TABLE_SCOPE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.ALL_TABLE_SCOPE_SUFFIX;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_ACCOUNT_LOCK;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_ACCOUNT_UNLOCK;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_ALTER_USER;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_CREATE_USER;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_DROP_USER;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_FROM;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_GRANT;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_IDENTIFIED_BY;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_REVOKE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_GRANTS_FOR;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_WITH_GRANT_OPTION;

import static ai.chat2db.plugin.mysql.constant.MysqlAccountSqlBuilderConstants.*;
class MysqlAccountSqlBuilder {


    private MysqlAccountSqlBuilder() {
    }

    static String buildSql(AccountOperationRequest command) {
        return buildSql(command, false);
    }

    static String buildDisplaySql(AccountOperationRequest command) {
        return buildSql(command, true);
    }

    private static String buildSql(AccountOperationRequest command, boolean maskSensitive) {
        validateBase(command);
        return switch (AccountActionTypeEnum.from(command.getActionType())) {
            case CREATE_USER -> {
                requirePassword(command);
                yield SQL_CREATE_USER + account(command) + SQL_IDENTIFIED_BY + passwordLiteral(command, maskSensitive);
            }
            case ALTER_PASSWORD -> {
                requirePassword(command);
                yield SQL_ALTER_USER + account(command) + SQL_IDENTIFIED_BY + passwordLiteral(command, maskSensitive);
            }
            case LOCK_ACCOUNT -> SQL_ALTER_USER + account(command) + SQL_ACCOUNT_LOCK;
            case UNLOCK_ACCOUNT -> SQL_ALTER_USER + account(command) + SQL_ACCOUNT_UNLOCK;
            case DROP_USER -> SQL_DROP_USER + account(command);
            case GRANT_PRIVILEGE -> {
                requirePrivileges(command);
                yield SQL_GRANT + privilegeList(command.getPrivileges()) + SQLConstants.SQL_ON + scope(command) + SQLConstants.SQL_TO
                        + account(command) + (Boolean.TRUE.equals(command.getGrantOption()) ? SQL_WITH_GRANT_OPTION : SQLConstants.EMPTY);
            }
            case REVOKE_PRIVILEGE -> {
                requirePrivileges(command);
                yield SQL_REVOKE + privilegeList(command.getPrivileges()) + SQLConstants.SQL_ON + scope(command) + SQL_FROM
                        + account(command);
            }
            case CREATE_ROLE -> SQL_CREATE_ROLE + account(command);
            case DROP_ROLE -> SQL_DROP_ROLE_IF_EXISTS + account(command);
            case GRANT_ROLE -> {
                String role = roleAccount(command);
                StringBuilder sb = new StringBuilder();
                sb.append(SQL_GRANT).append(role).append(SQLConstants.SQL_TO).append(account(command));
                if (Boolean.TRUE.equals(command.getWithAdminOption())) {
                    sb.append(SQL_WITH_ADMIN_OPTION);
                }
                yield sb.toString();
            }
            case REVOKE_ROLE -> {
                yield SQL_REVOKE + roleAccount(command) + SQL_FROM + account(command);
            }
            case SET_DEFAULT_ROLE -> {
                DefaultRoleModeEnum mode = DefaultRoleModeEnum.from(command.getDefaultRoleMode());
                StringBuilder sb = new StringBuilder();
                sb.append(SQL_SET_DEFAULT_ROLE);
                switch (mode) {
                    case ALL -> sb.append(SQL_ROLE_ALL);
                    case NONE -> sb.append(SQL_ROLE_NONE);
                    case SELECTED -> {
                        if (command.getRoleList() == null || command.getRoleList().isEmpty()) {
                            throw new BusinessException(ERROR_KEY_ACCOUNT_ACTION_UNSUPPORTED);
                        }
                        sb.append(command.getRoleList().stream()
                                .map(role -> account(role, command.getRoleHost()))
                                .collect(Collectors.joining(", ")));
                    }
                }
                sb.append(SQLConstants.SQL_TO).append(account(command));
                yield sb.toString();
            }
            default -> throw new BusinessException(ERROR_KEY_ACCOUNT_ACTION_UNSUPPORTED);
        };
    }

    private static String passwordLiteral(AccountOperationRequest command, boolean maskSensitive) {
        return maskSensitive ? MASKED_PASSWORD_LITERAL : stringLiteral(command.getPassword());
    }

    static String previewToken(String sql) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256_ALGORITHM);
            byte[] hash = digest.digest(sql.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format(HEX_BYTE_FORMAT, b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String account(AccountOperationRequest command) {
        return account(command.getUser(), command.getHost());
    }

    static String account(String user, String host) {
        validateAccountPart(user, ERROR_KEY_ACCOUNT_USER_REQUIRED);
        validateAccountPart(host, ERROR_KEY_ACCOUNT_HOST_REQUIRED);
        return stringLiteral(user) + SQLConstants.AT + stringLiteral(host);
    }

    static String showGrantsSql(String user, String host) {
        return SQL_SHOW_GRANTS_FOR + account(user, host);
    }

    static String identifier(String name) {
        if (StringUtils.isBlank(name)) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_IDENTIFIER_REQUIRED);
        }
        return SQLConstants.BACK_QUOTE + name.replace(SQLConstants.BACK_QUOTE, ESCAPED_BACK_QUOTE) + SQLConstants.BACK_QUOTE;
    }

    static String stringLiteral(String value) {
        if (value == null) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_LITERAL_REQUIRED);
        }
        return SQLConstants.SINGLE_QUOTE + value.replace(SQLConstants.BACKSLASH, ESCAPED_BACKSLASH).replace(SQLConstants.SINGLE_QUOTE, ESCAPED_SINGLE_QUOTE) + SQLConstants.SINGLE_QUOTE;
    }

    static String scopeSql(AccountOperationRequest command) {
        switch (PrivilegeScopeEnum.from(command.getScope())) {
            case GLOBAL:
                return ALL_DATABASE_ALL_TABLE_SCOPE;
            case DATABASE:
                if (StringUtils.isBlank(command.getDatabaseName())) {
                    throw new BusinessException(ERROR_KEY_ACCOUNT_DATABASE_REQUIRED);
                }
                return identifier(command.getDatabaseName()) + ALL_TABLE_SCOPE_SUFFIX;
            case TABLE:
                if (StringUtils.isBlank(command.getDatabaseName())) {
                    throw new BusinessException(ERROR_KEY_ACCOUNT_DATABASE_REQUIRED);
                }
                if (StringUtils.isBlank(command.getTableName())) {
                    throw new BusinessException(ERROR_KEY_ACCOUNT_TABLE_REQUIRED);
                }
                return identifier(command.getDatabaseName()) + SQLConstants.DOT + identifier(command.getTableName());
            default:
                throw new BusinessException(ERROR_KEY_ACCOUNT_SCOPE_UNSUPPORTED);
        }
    }

    private static String scope(AccountOperationRequest command) {
        return scopeSql(command);
    }

    private static String privilegeList(List<String> privileges) {
        String sqlPrivileges = privileges.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(MysqlPrivilege::sqlName)
                .collect(Collectors.joining(SQLConstants.COMMA_SPACE));
        if (StringUtils.isBlank(sqlPrivileges)) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_PRIVILEGE_REQUIRED);
        }
        return sqlPrivileges;
    }

    private static void validateBase(AccountOperationRequest command) {
        if (command == null || command.getActionType() == null) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_ACTION_REQUIRED);
        }
        validateAccountPart(command.getUser(), ERROR_KEY_ACCOUNT_USER_REQUIRED);
        validateAccountPart(command.getHost(), ERROR_KEY_ACCOUNT_HOST_REQUIRED);
    }

    private static void requirePassword(AccountOperationRequest command) {
        if (StringUtils.isBlank(command.getPassword())) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_PASSWORD_REQUIRED);
        }
    }

    private static void requirePrivileges(AccountOperationRequest command) {
        if (command.getPrivileges() == null || command.getPrivileges().isEmpty()) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_PRIVILEGE_REQUIRED);
        }
    }

    private static String roleAccount(AccountOperationRequest command) {
        if (StringUtils.isBlank(command.getRoleName())) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_ROLE_REQUIRED);
        }
        return account(command.getRoleName(), command.getRoleHost());
    }

    private static void validateAccountPart(String value, String code) {
        if (StringUtils.isBlank(value)) {
            throw new BusinessException(code);
        }
        if (value.indexOf('\0') >= 0) {
            throw new BusinessException(ERROR_KEY_ACCOUNT_INVALID_ACCOUNT_NAME);
        }
    }
}

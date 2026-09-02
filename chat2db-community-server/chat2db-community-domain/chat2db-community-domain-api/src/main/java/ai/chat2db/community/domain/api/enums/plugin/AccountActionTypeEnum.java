package ai.chat2db.community.domain.api.enums.plugin;

import ai.chat2db.community.tools.exception.BusinessException;

public enum AccountActionTypeEnum {
    CREATE_USER,
    ALTER_PASSWORD,
    LOCK_ACCOUNT,
    UNLOCK_ACCOUNT,
    DROP_USER,
    GRANT_PRIVILEGE,
    REVOKE_PRIVILEGE,
    ALTER_PASSWORD_POLICY,
    ALTER_RESOURCE_LIMITS,
    CREATE_ROLE,
    DROP_ROLE,
    GRANT_ROLE,
    REVOKE_ROLE,
    SET_DEFAULT_ROLE;

    public static AccountActionTypeEnum from(String value) {
        if (value == null) {
            throw new BusinessException("mysql.account.actionRequired");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("mysql.account.actionUnsupported", null, e);
        }
    }
}

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
    RENAME_USER;

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

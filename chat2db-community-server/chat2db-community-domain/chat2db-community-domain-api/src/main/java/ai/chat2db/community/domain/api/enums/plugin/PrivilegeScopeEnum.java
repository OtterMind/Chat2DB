package ai.chat2db.community.domain.api.enums.plugin;

import ai.chat2db.community.tools.exception.BusinessException;

public enum PrivilegeScopeEnum {
    GLOBAL,
    DATABASE,
    TABLE,
    COLUMN;

    public static PrivilegeScopeEnum from(String value) {
        if (value == null) {
            throw new BusinessException("mysql.account.scopeRequired");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("mysql.account.scopeUnsupported", null, e);
        }
    }
}

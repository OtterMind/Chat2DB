package ai.chat2db.community.domain.api.enums.plugin;

import ai.chat2db.community.tools.exception.BusinessException;

public enum DefaultRoleModeEnum {
    ALL,
    NONE,
    SELECTED;

    public static DefaultRoleModeEnum from(String value) {
        if (value == null) {
            throw new BusinessException("mysql.account.defaultRoleModeRequired");
        }
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("mysql.account.defaultRoleModeUnsupported", null, e);
        }
    }
}

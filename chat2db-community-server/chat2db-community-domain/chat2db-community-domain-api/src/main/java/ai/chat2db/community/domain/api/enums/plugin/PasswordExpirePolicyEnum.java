package ai.chat2db.community.domain.api.enums.plugin;

import ai.chat2db.community.tools.exception.BusinessException;

public enum PasswordExpirePolicyEnum {
    DEFAULT,
    NEVER,
    IMMEDIATE,
    INTERVAL;

    public static PasswordExpirePolicyEnum from(String value) {
        if (value == null) {
            throw new BusinessException("mysql.account.passwordPolicyRequired");
        }
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("mysql.account.passwordPolicyUnsupported", null, e);
        }
    }
}

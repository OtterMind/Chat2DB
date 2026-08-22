package ai.chat2db.community.domain.api.enums.plugin;

import ai.chat2db.community.tools.exception.BusinessException;

public enum TlsRequirementEnum {
    NONE,
    SSL,
    X509,
    SPECIFIED;

    public static TlsRequirementEnum from(String value) {
        if (value == null) {
            throw new BusinessException("mysql.account.tlsRequirementRequired");
        }
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("mysql.account.tlsRequirementUnsupported", null, e);
        }
    }
}

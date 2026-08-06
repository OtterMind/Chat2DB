package ai.chat2db.community.domain.api.enums.plugin;

import ai.chat2db.community.tools.exception.BusinessException;

public enum TableMaintenanceTypeEnum {
    ANALYZE,
    OPTIMIZE,
    CHECK,
    REPAIR;

    public static TableMaintenanceTypeEnum from(String value) {
        if (value == null) {
            throw new BusinessException("mysql.maintenance.typeRequired");
        }
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("mysql.maintenance.typeUnsupported", null, e);
        }
    }
}

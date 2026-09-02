package ai.chat2db.community.domain.api.enums.plugin;

import ai.chat2db.community.tools.exception.BusinessException;

import java.util.Locale;
import java.util.Set;

public enum TableMaintenanceTypeEnum {
    ANALYZE,
    OPTIMIZE,
    CHECK,
    REPAIR;

    private static final Set<String> MYSQL_REPAIR_ENGINES = Set.of("MYISAM", "ARCHIVE", "CSV");

    public static TableMaintenanceTypeEnum from(String value) {
        if (value == null) {
            throw new BusinessException("mysql.maintenance.typeRequired");
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("mysql.maintenance.typeUnsupported", null, e);
        }
    }

    public boolean supportsMysqlStorageEngine(String engine) {
        if (this != REPAIR) {
            return true;
        }
        if (engine == null) {
            return false;
        }
        return MYSQL_REPAIR_ENGINES.contains(engine.trim().toUpperCase(Locale.ROOT));
    }
}

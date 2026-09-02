package ai.chat2db.community.domain.api.enums.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableMaintenanceTypeEnumTest {

    @Test
    void mysql57RepairTableIsRestrictedToRepairableEngines() {
        assertTrue(TableMaintenanceTypeEnum.REPAIR.supportsMysqlStorageEngine("MyISAM"));
        assertTrue(TableMaintenanceTypeEnum.REPAIR.supportsMysqlStorageEngine("ARCHIVE"));
        assertTrue(TableMaintenanceTypeEnum.REPAIR.supportsMysqlStorageEngine("CSV"));

        assertFalse(TableMaintenanceTypeEnum.REPAIR.supportsMysqlStorageEngine("InnoDB"));
        assertFalse(TableMaintenanceTypeEnum.REPAIR.supportsMysqlStorageEngine("MEMORY"));
    }

    @Test
    void mysql80RepairTableKeepsTheSameEngineGate() {
        assertTrue(TableMaintenanceTypeEnum.REPAIR.supportsMysqlStorageEngine("myisam"));
        assertTrue(TableMaintenanceTypeEnum.REPAIR.supportsMysqlStorageEngine("archive"));
        assertTrue(TableMaintenanceTypeEnum.REPAIR.supportsMysqlStorageEngine("csv"));

        assertFalse(TableMaintenanceTypeEnum.REPAIR.supportsMysqlStorageEngine("innodb"));
        assertFalse(TableMaintenanceTypeEnum.REPAIR.supportsMysqlStorageEngine(null));
    }
}

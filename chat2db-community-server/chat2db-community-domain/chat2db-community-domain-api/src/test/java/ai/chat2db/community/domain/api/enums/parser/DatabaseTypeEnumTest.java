package ai.chat2db.community.domain.api.enums.parser;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTypeEnumTest {

    @Test
    void identifiesMysqlProtocolFamily() {
        EnumSet<DatabaseTypeEnum> mysqlFamily = EnumSet.of(
                DatabaseTypeEnum.MYSQL,
                DatabaseTypeEnum.MARIADB,
                DatabaseTypeEnum.TIDB,
                DatabaseTypeEnum.STARROCKS,
                DatabaseTypeEnum.DORIS,
                DatabaseTypeEnum.OCEANBASE);

        for (DatabaseTypeEnum type : DatabaseTypeEnum.values()) {
            if (mysqlFamily.contains(type)) {
                assertTrue(type.isMysqlProtocolFamily(), type.name());
            } else {
                assertFalse(type.isMysqlProtocolFamily(), type.name());
            }
        }
    }
}

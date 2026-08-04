package ai.chat2db.community.domain.api.model.pin;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for code-review finding core:domain-api-5:
 * instances equal by the four key fields must have equal hash codes,
 * even when their ids differ.
 */
class PinTableTest {

    private PinTable build(Long id) {
        PinTable pinTable = new PinTable();
        pinTable.setId(id);
        pinTable.setDataSourceId(1L);
        pinTable.setDatabaseName("db");
        pinTable.setSchemaName("schema");
        pinTable.setTableName("table");
        return pinTable;
    }

    @Test
    void equalInstancesHaveEqualHashCodesDespiteDifferentIds() {
        PinTable a = build(1L);
        PinTable b = build(2L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void hashSetDoesNotDuplicateEqualInstances() {
        Set<PinTable> set = new HashSet<>();
        set.add(build(1L));
        set.add(build(2L));
        assertEquals(1, set.size());
        assertTrue(set.contains(build(3L)));
    }
}

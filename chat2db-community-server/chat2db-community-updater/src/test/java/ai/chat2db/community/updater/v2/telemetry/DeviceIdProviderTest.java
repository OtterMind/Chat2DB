package ai.chat2db.community.updater.v2.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceIdProviderTest {

    @Test
    void machineIdIsHashedIntoAStableDeviceId() {
        String deviceId = DeviceIdProvider.derive("ABC-123");

        assertEquals(deviceId, DeviceIdProvider.derive("abc-123 "), "case and padding do not matter");
        assertTrue(deviceId.startsWith("d1_"), deviceId);
        assertNotEquals(deviceId, DeviceIdProvider.derive("abc-124"));
    }

    @Test
    void randomIdIsPrefixedAndUnique() {
        String first = DeviceIdProvider.randomId();

        assertTrue(first.startsWith("r1_"), first);
        assertNotEquals(first, DeviceIdProvider.randomId());
    }
}

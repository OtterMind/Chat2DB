package ai.chat2db.community.start.ai.subscription.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemKeyringAvailabilityProbeTest {

    @Test
    void macAvailabilityRequiresResolvableDefaultUserKeychain() {
        assertTrue(new SystemKeyringAvailabilityProbe(
                "Mac OS X", null, null, () -> true).isKeyringAvailable());
        assertFalse(new SystemKeyringAvailabilityProbe(
                "Mac OS X", null, null, () -> false).isKeyringAvailable());
    }

    @Test
    void macProbeFailureFailsClosed() {
        SystemKeyringAvailabilityProbe probe = new SystemKeyringAvailabilityProbe(
                "Mac OS X", null, null, () -> {
                    throw new IllegalStateException("probe failed");
                });

        assertFalse(probe.isKeyringAvailable());
    }

    @Test
    void nonMacChecksRemainPlatformSpecific() {
        assertTrue(new SystemKeyringAvailabilityProbe(
                "Linux", null, "unix:path=/run/user/1000/bus", () -> false).isKeyringAvailable());
        assertFalse(new SystemKeyringAvailabilityProbe(
                "Linux", null, "", () -> true).isKeyringAvailable());
        assertFalse(new SystemKeyringAvailabilityProbe(
                "Plan 9", null, null, () -> true).isKeyringAvailable());
    }
}

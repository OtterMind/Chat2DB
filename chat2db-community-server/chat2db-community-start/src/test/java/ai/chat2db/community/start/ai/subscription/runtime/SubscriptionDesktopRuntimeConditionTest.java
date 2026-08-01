package ai.chat2db.community.start.ai.subscription.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionDesktopRuntimeConditionTest {

    @Test
    void registersOnlyWhenEveryStaticSurfaceGatePasses() {
        assertTrue(SubscriptionDesktopRuntimeCondition.matchesValues(true, true, true, true, true));
        assertFalse(SubscriptionDesktopRuntimeCondition.matchesValues(false, true, true, true, true));
        assertFalse(SubscriptionDesktopRuntimeCondition.matchesValues(true, false, true, true, true));
        assertFalse(SubscriptionDesktopRuntimeCondition.matchesValues(true, true, false, true, true));
        assertFalse(SubscriptionDesktopRuntimeCondition.matchesValues(true, true, true, false, true));
        assertFalse(SubscriptionDesktopRuntimeCondition.matchesValues(true, true, true, true, false));
    }
}

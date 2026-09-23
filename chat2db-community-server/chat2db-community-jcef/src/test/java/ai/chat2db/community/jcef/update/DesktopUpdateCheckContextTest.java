package ai.chat2db.community.jcef.update;

import ai.chat2db.community.updater.v2.telemetry.TelemetryTrigger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopUpdateCheckContextTest {

    @Test
    void readsTriggerAndOfflineFlag() {
        DesktopUpdateCheckContext context =
            DesktopUpdateCheckContext.parse("{\"trigger\":\"scheduled\",\"offlineActivation\":true}");

        assertEquals(TelemetryTrigger.SCHEDULED, context.trigger());
        assertTrue(context.offlineActivation());
    }

    @Test
    void unknownTriggerAndMissingFlagFallBackToDefaults() {
        DesktopUpdateCheckContext context = DesktopUpdateCheckContext.parse("{}");

        assertEquals(TelemetryTrigger.UNKNOWN, context.trigger());
        assertFalse(context.offlineActivation());
    }

    @Test
    void malformedRequestFallsBackToDefaults() {
        DesktopUpdateCheckContext context = DesktopUpdateCheckContext.parse("{not json");

        assertEquals(TelemetryTrigger.UNKNOWN, context.trigger());
        assertFalse(context.offlineActivation());
    }
}

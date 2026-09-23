package ai.chat2db.community.updater.v2.telemetry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopUsageTelemetryTest {

    @Test
    void offlineActivationIsNeverReported() {
        List<UsageTelemetryReporter.Report> reports = new ArrayList<>();
        DesktopUsageTelemetry telemetry = new DesktopUsageTelemetry(reports::add);

        telemetry.reportCheck(TelemetryTrigger.STARTUP, true, "5.3.7", false, true, "5.3.8");

        assertTrue(reports.isEmpty());
    }

    @Test
    void reportsTriggerResultAndRuntimeDetails() {
        List<UsageTelemetryReporter.Report> reports = new ArrayList<>();
        DesktopUsageTelemetry telemetry = new DesktopUsageTelemetry(reports::add);

        telemetry.reportCheck(TelemetryTrigger.SCHEDULED, false, "5.3.7", true, true, "5.3.8");

        assertEquals(1, reports.size());
        UsageTelemetryReporter.Report report = reports.get(0);
        assertEquals("BETA", report.data().get("channel"));
        assertEquals("5.3.7", report.data().get("version"));
        assertEquals("scheduled", report.data().get("trigger"));
        assertEquals("available", report.data().get("result"));
        assertEquals("5.3.8", report.data().get("latestVersion"));
        assertEquals(report.productLabel(), report.data().get("product"));
        assertTrue(report.data().containsKey("activityDate"));
        assertTrue(report.data().containsKey("timezone"));
    }

    @Test
    void omitsLatestVersionWhenNothingIsAvailable() {
        List<UsageTelemetryReporter.Report> reports = new ArrayList<>();
        DesktopUsageTelemetry telemetry = new DesktopUsageTelemetry(reports::add);

        telemetry.reportCheck(TelemetryTrigger.MANUAL, false, "5.3.7", false, false, "");

        assertEquals(1, reports.size());
        assertEquals("no_update", reports.get(0).data().get("result"));
        assertTrue(!reports.get(0).data().containsKey("latestVersion"));
    }

    @Test
    void omitsVersionWhenItIsUnknown() {
        List<UsageTelemetryReporter.Report> reports = new ArrayList<>();
        DesktopUsageTelemetry telemetry = new DesktopUsageTelemetry(reports::add);

        telemetry.reportCheck(TelemetryTrigger.STARTUP, false, "  ", false, false, "");

        assertEquals(1, reports.size());
        assertTrue(!reports.get(0).data().containsKey("version"));
    }

    @Test
    void mapsRuntimeModeAndPlatformLabels() {
        assertEquals("Community", DesktopUsageTelemetry.productLabel("community"));
        assertEquals("Pro", DesktopUsageTelemetry.productLabel("PRO"));
        assertEquals("Local", DesktopUsageTelemetry.productLabel(" local "));
        assertEquals("Unknown", DesktopUsageTelemetry.productLabel(""));
    }
}

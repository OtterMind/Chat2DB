package ai.chat2db.community.sqlx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.chat2db.community.tools.config.SystemSettingConstant;
import ai.chat2db.community.tools.sqlx.SqlxException;
import ai.chat2db.community.tools.sqlx.SqlxStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlxStatusServiceTest {

    @Test
    void comparesReleaseVersionsNumerically() {
        assertTrue(SqlxStatusService.compareVersions("0.1.16", "0.1.15") > 0);
        assertTrue(SqlxStatusService.compareVersions("0.1.9", "0.1.15") < 0);
        assertEquals(0, SqlxStatusService.compareVersions("0.1.16", "0.1.16"));
        assertEquals(0, SqlxStatusService.compareVersions("0.1.16-rc1", "0.1.16"));
        assertTrue(SqlxStatusService.compareVersions("0.2.0", "0.1.99") > 0);
        assertTrue(SqlxStatusService.compareVersions("1.0.0", "0.9.9") > 0);
    }

    @Test
    void parsesAnAvailableUpdate() {
        String output = "{\"success\":true,\"data\":{\"status\":\"update_available\","
                + "\"latest_version\":\"0.1.16\",\"checked_at\":123}}";
        Map<String, Object> latest = SqlxStatusService.parseCheckResult(result(output), "0.1.15");
        assertEquals("updateAvailable", latest.get("status"));
        assertEquals("0.1.16", latest.get("version"));
    }

    @Test
    void reportsUpToDateWhenNoNewerVersionExists() {
        String output = "{\"success\":true,\"data\":{\"status\":\"up_to_date\",\"latest_version\":\"0.1.16\"}}";
        assertEquals("upToDate", SqlxStatusService.parseCheckResult(result(output), "0.1.16").get("status"));
        String older = "{\"success\":true,\"data\":{\"status\":\"up_to_date\",\"latest_version\":\"0.1.15\"}}";
        assertEquals("upToDate", SqlxStatusService.parseCheckResult(result(older), "0.1.16").get("status"));
    }

    @Test
    void treatsTheRealUpdaterRecordAsUpToDate() {
        // Captured from `sqlx update check` on an installation that is already the latest release.
        String output = "{\"success\":true,\"data\":{\"source\":\"https://github.com/OtterMind/sqlx/releases\","
                + "\"current_version\":\"0.1.15\",\"checked_at\":1790163452,\"status\":\"up_to_date\","
                + "\"latest_version\":\"0.1.15\",\"release_url\":\"https://github.com/OtterMind/sqlx/releases/tag/v0.1.15\","
                + "\"error\":null,\"notified_at\":null,\"notified_version\":null}}";
        Map<String, Object> latest = SqlxStatusService.parseCheckResult(result(output), "0.1.15");
        assertEquals("upToDate", latest.get("status"));
        assertEquals("0.1.15", latest.get("version"));
    }

    @Test
    void reportsAFailedCheckWithoutLosingTheReason() {
        Map<String, Object> unreadable = SqlxStatusService.parseCheckResult(result("not json"), "0.1.16");
        assertEquals("checkFailed", unreadable.get("status"));
        assertTrue(String.valueOf(unreadable.get("error")).contains("not json"));

        String failed = "{\"success\":true,\"data\":{\"status\":\"check_failed\",\"error\":\"network unreachable\"}}";
        Map<String, Object> reported = SqlxStatusService.parseCheckResult(result(failed), "0.1.16");
        assertEquals("checkFailed", reported.get("status"));
        assertEquals("network unreachable", reported.get("error"));
    }

    @Test
    void rejectsAnExecutableThatIsNotOtterMindSqlx(@TempDir Path directory) throws IOException {
        Path foreign = directory.resolve("sqlx");
        Files.writeString(foreign, "not a program");
        SqlxStatusService service = new SqlxStatusService(
                SqlxSettingsStore.inMemory(new HashMap<>()), new SqlxReleaseInstaller());
        SqlxException failure = assertThrows(SqlxException.class, () -> service.useBinary(foreign.toString()));
        assertEquals("sqlx.invalid_binary", failure.getCode());
        assertThrows(SqlxException.class, () -> service.useBinary(" "));
    }

    @Test
    void statusCarriesTheRendererContractWithoutAnInstallation() {
        SqlxStatusService service = new SqlxStatusService(
                SqlxSettingsStore.inMemory(new HashMap<>()), new SqlxReleaseInstaller());
        SqlxStatus status = service.status();
        assertFalse(status.getInstallDir().isBlank());
        assertTrue(status.getPlatform().equals("mac") || status.getPlatform().equals("windows")
                || status.getPlatform().equals("linux"), status.getPlatform());
        assertTrue(SqlxStatus.STATE_NOT_INSTALLED.equals(status.getState())
                        || SqlxStatus.STATE_CONFLICT.equals(status.getState())
                        || SqlxStatus.STATE_UNSUPPORTED.equals(status.getState())
                        || SqlxStatus.STATE_INSTALLED.equals(status.getState()),
                status.getState());
        assertEquals("unknown", status.getLatest().get("status"));
    }

    @Test
    void recordedLatestVersionSurvivesIntoTheSnapshot() {
        Map<String, String> stored = new HashMap<>();
        stored.put(SystemSettingConstant.SQLX_LATEST_STATUS, "updateAvailable");
        stored.put(SystemSettingConstant.SQLX_LATEST_VERSION, "0.1.17");
        stored.put(SystemSettingConstant.SQLX_LATEST_CHECKED_AT, "1700000000000");
        SqlxStatusService service = new SqlxStatusService(
                SqlxSettingsStore.inMemory(stored), new SqlxReleaseInstaller());
        Map<String, Object> latest = service.status().getLatest();
        assertEquals("updateAvailable", latest.get("status"));
        assertEquals("0.1.17", latest.get("version"));
        assertEquals(1700000000000L, latest.get("checkedAt"));
    }

    @Test
    void anUpdateThatIsAlreadyInstalledIsNotAnnounced() {
        Map<String, Object> recorded = new HashMap<>(Map.of("status", "updateAvailable", "version", "0.1.16"));
        // The stored check describes the version that was replaced; it must not contradict the binary.
        assertEquals("upToDate", SqlxStatusService.reconcileLatest(recorded, "0.1.16").get("status"));
        assertEquals("upToDate", SqlxStatusService.reconcileLatest(recorded, "0.1.17").get("status"));
        assertEquals("updateAvailable", SqlxStatusService.reconcileLatest(recorded, "0.1.15").get("status"));
        assertEquals("updateAvailable", SqlxStatusService.reconcileLatest(recorded, null).get("status"));
        Map<String, Object> none = new HashMap<>(Map.of("status", "unknown"));
        assertEquals("unknown", SqlxStatusService.reconcileLatest(none, "0.1.16").get("status"));
    }

    @Test
    void parsesTheImportReportAndKeepsEverySkipReason() {
        String output = "{\"success\":true,\"data\":{\"version\":1,\"mode\":\"merge\",\"added\":2,\"updated\":1,"
                + "\"unchanged\":0,\"total\":3,\"datasources\":[{\"id\":\"a\",\"name\":\"dev\"}],"
                + "\"skipped\":[{\"name\":\"odd\",\"reason\":\"invalid_connection\",\"detail\":\"missing host\"}]}}";
        Map<String, Object> summary = SqlxStatusService.parseImportReport(output);
        assertEquals(2, summary.get("added"));
        assertEquals(1, summary.get("updated"));
        assertEquals(0, summary.get("unchanged"));
        assertEquals(3, summary.get("total"));
        assertEquals(1, ((java.util.List<?>) summary.get("datasources")).size());
        assertEquals(1, ((java.util.List<?>) summary.get("skipped")).size());
    }

    @Test
    void unreadableImportOutputFailsInsteadOfReportingSuccess() {
        SqlxException failure = assertThrows(SqlxException.class,
                () -> SqlxStatusService.parseImportReport("sqlx: error: unrecognized subcommand"));
        assertEquals("sqlx.import_failed", failure.getCode());
        assertTrue(failure.getMessage().contains("unrecognized subcommand"));
        assertThrows(SqlxException.class, () -> SqlxStatusService.parseImportReport(""));
    }

    @Test
    void importRefusesAnEmptySelectionAndReportsNothingToImport() {
        SqlxStatusService service = new SqlxStatusService(
                SqlxSettingsStore.inMemory(new HashMap<>()), new SqlxReleaseInstaller());
        SqlxException empty = assertThrows(SqlxException.class, () -> service.importDatasources(List.of()));
        assertEquals("sqlx.no_datasources", empty.getCode());
        // A selected id that yields no readable datasource must never look like a success.
        SqlxException unusable = assertThrows(SqlxException.class, () -> service.importDatasources(List.of(1L)));
        assertTrue(
                List.of("sqlx.no_datasources", "sqlx.not_installed").contains(unusable.getCode()),
                unusable.getCode());
    }

    private static SqlxProcessRunner.Result result(String output) {
        return new SqlxProcessRunner.Result(0, output, "", false);
    }
}

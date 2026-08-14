package ai.chat2db.community.jcef.update;

public record DesktopUpdateCheckResult(
        boolean needsUpdate,
        String version,
        String releaseNotes,
        boolean checkFailed,
        String releasePageUrl,
        UpdateFailureStage failureStage,
        UpdateFailureReason failureReason) {

    public static DesktopUpdateCheckResult notAvailable() {
        return new DesktopUpdateCheckResult(false, "", "", false, null, null, null);
    }

    public static DesktopUpdateCheckResult available(String version, String releaseNotes, String releasePageUrl) {
        return new DesktopUpdateCheckResult(true, version, releaseNotes == null ? "" : releaseNotes, false,
                releasePageUrl, null, null);
    }

    public static DesktopUpdateCheckResult failed(String releasePageUrl, UpdateFailureStage stage, UpdateFailureReason reason) {
        return new DesktopUpdateCheckResult(false, "", "", true, releasePageUrl, stage, reason);
    }
}

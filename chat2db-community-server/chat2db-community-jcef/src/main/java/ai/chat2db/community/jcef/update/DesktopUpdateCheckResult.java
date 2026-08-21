package ai.chat2db.community.jcef.update;

public record DesktopUpdateCheckResult(boolean needsUpdate, String version, boolean checkFailed) {

    public DesktopUpdateCheckResult(boolean needsUpdate, String version) {
        this(needsUpdate, version, false);
    }

    public static DesktopUpdateCheckResult notAvailable() {
        return new DesktopUpdateCheckResult(false, "", false);
    }

    public static DesktopUpdateCheckResult failed() {
        return new DesktopUpdateCheckResult(false, "", true);
    }
}

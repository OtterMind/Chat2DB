package ai.chat2db.community.jcef.update;

public record DesktopUpdateCheckResult(boolean needsUpdate, String version) {

    public static DesktopUpdateCheckResult notAvailable() {
        return new DesktopUpdateCheckResult(false, "");
    }
}

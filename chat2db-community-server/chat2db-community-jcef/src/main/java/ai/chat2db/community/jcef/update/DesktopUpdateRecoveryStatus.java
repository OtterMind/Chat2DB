package ai.chat2db.community.jcef.update;

public record DesktopUpdateRecoveryStatus(
    boolean failed,
    String fromVersion,
    String toVersion
) {

    public static DesktopUpdateRecoveryStatus none() {
        return new DesktopUpdateRecoveryStatus(false, "", "");
    }
}

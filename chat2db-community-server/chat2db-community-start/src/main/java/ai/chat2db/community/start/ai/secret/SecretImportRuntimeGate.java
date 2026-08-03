package ai.chat2db.community.start.ai.secret;

/**
 * Fail-closed runtime gate for the legacy secret-import bridge.
 */
public final class SecretImportRuntimeGate {

    private SecretImportRuntimeGate() {
    }

    public static boolean isEnabled(
            boolean featureEnabled,
            boolean communityRuntime,
            boolean desktopRuntime,
            boolean guiEnabled,
            boolean releaseProfile) {
        return featureEnabled && communityRuntime && desktopRuntime && guiEnabled && releaseProfile;
    }
}

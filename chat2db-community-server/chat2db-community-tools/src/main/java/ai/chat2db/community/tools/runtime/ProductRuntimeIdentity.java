package ai.chat2db.community.tools.runtime;

/**
 * Product-specific runtime values consumed by the reusable Community implementation.
 */
public interface ProductRuntimeIdentity {

    default int priority() {
        return 0;
    }

    boolean communityRuntime();

    boolean offlineRuntime();

    String runtimeMode();

    String networkStatus();

    String stateDirectoryName();

    String settingsDirectoryName();

    String runtimeConfigFileName(String environment);

    String clientIdFileName();

    String displayName();

    String protocolScheme();

    String updateBaseUrl();
}

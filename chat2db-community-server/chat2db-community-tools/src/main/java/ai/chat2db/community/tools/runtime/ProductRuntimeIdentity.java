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

    default String localStateNamespace() {
        return "community";
    }

    /**
     * Config file name used by releases that predate
     * {@link #runtimeConfigFileName(String)}; used only to migrate existing
     * installations once, so a product whose legacy name differs can override.
     */
    default String legacyRuntimeConfigFileName(String environment) {
        return "enterprise_config_" + environment + ".json";
    }

    /**
     * Client id file name used by releases that predate
     * {@link #clientIdFileName()}; used only to migrate existing
     * installations once.
     */
    default String legacyClientIdFileName() {
        return "enterprise_client_uuid";
    }

    String displayName();

    String protocolScheme();

    String updateBaseUrl();
}

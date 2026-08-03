package ai.chat2db.community.start.ai.subscription.appserver;

/**
 * Probes whether OS Keyring storage is available for app-server credentials.
 * File credential fallback is forbidden.
 */
@FunctionalInterface
public interface KeyringAvailabilityProbe {

    boolean isKeyringAvailable();
}

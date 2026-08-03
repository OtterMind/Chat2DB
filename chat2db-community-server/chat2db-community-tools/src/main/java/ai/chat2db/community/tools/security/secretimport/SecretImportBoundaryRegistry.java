package ai.chat2db.community.tools.security.secretimport;

/**
 * Process-wide registration for the secret-import boundary used by the JCEF early interceptor.
 * Lead integration should register a Spring-wired instance after the model-config port is ready.
 */
public final class SecretImportBoundaryRegistry {

    private static volatile SecretImportBoundary boundary;

    private SecretImportBoundaryRegistry() {
    }

    public static void register(SecretImportBoundary secretImportBoundary) {
        boundary = secretImportBoundary;
    }

    public static void clear() {
        SecretImportBoundary current = boundary;
        if (current != null) {
            // best-effort; boundary may own service lifecycle via Spring
        }
        boundary = null;
    }

    public static SecretImportBoundary getBoundary() {
        return boundary;
    }

    public static boolean isReady() {
        return boundary != null;
    }
}

package ai.chat2db.community.tools.security.secretimport;

/**
 * Log-safe helpers for the secret-import boundary.
 * IDs may be attacker-controlled; never emit control characters or unbounded text.
 */
public final class SecretImportSafety {

    private static final int LOG_ID_MAX = 8;

    private SecretImportSafety() {
    }

    /**
     * Truncate and strip control/non-printable characters from identifiers used in logs.
     * Never use this for cryptographic material.
     */
    public static String safeId(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        StringBuilder sanitized = new StringBuilder(LOG_ID_MAX);
        for (int i = 0; i < value.length() && sanitized.length() < LOG_ID_MAX; i++) {
            char c = value.charAt(i);
            // Printable ASCII only — rejects CR/LF/TAB and other control chars.
            if (c >= 0x20 && c <= 0x7E) {
                sanitized.append(c);
            }
        }
        return sanitized.length() == 0 ? "-" : sanitized.toString();
    }
}

package ai.chat2db.community.tools.security.secretimport;

/**
 * Hard size bounds for the encrypted secret-import boundary (JCEF memory DoS defense).
 */
public final class SecretImportLimits {

    /** Max characters for the entire raw javaQuery payload. */
    public static final int MAX_RAW_QUERY_CHARS = 256 * 1024;

    /** Max characters for the secret-import message body (envelope JSON). */
    public static final int MAX_BODY_CHARS = 128 * 1024;

    /** Max characters for attemptId / itemId / uuid routing fields. */
    public static final int MAX_ID_CHARS = 128;

    /** Max Base64 characters for a 12-byte GCM nonce (with headroom). */
    public static final int MAX_NONCE_BASE64_CHARS = 64;

    /** Max Base64 characters for RSA-OAEP wrapped AES-256 key (~256 raw bytes). */
    public static final int MAX_WRAPPED_KEY_BASE64_CHARS = 512;

    /** Max Base64 characters for AES-GCM ciphertext of one legacy model config. */
    public static final int MAX_CIPHERTEXT_BASE64_CHARS = 48 * 1024;

    private SecretImportLimits() {
    }

    public static boolean exceeds(String value, int maxChars) {
        return value != null && value.length() > maxChars;
    }
}

package ai.chat2db.community.tools.security.secretimport;

/**
 * Safe, non-reversible error codes for the encrypted API-key import boundary.
 * Never attach secret material, ciphertext fragments, or exception causes to these codes.
 */
public enum SecretImportErrorCode {
    INVALID_ENVELOPE,
    UNSUPPORTED_SCHEMA,
    ATTEMPT_NOT_FOUND,
    ATTEMPT_EXPIRED,
    ATTEMPT_CANCELLED,
    ATTEMPT_COMPLETED,
    NONCE_REPLAY,
    DECRYPT_FAILED,
    PERSISTENCE_FAILED,
    IMPORT_OUTCOME_UNKNOWN,
    BACKEND_NOT_READY,
    INVALID_PAYLOAD,
    PAYLOAD_TOO_LARGE
}

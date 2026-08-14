package ai.chat2db.community.jcef.update;

/**
 * Reason for an update failure.
 */
public enum UpdateFailureReason {
    NETWORK,
    INVALID_MANIFEST,
    CHECKSUM_MISMATCH,
    UNSUPPORTED_REDIRECT,
    UNKNOWN
}

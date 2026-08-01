package ai.chat2db.community.start.ai.subscription.lifecycle;

/**
 * Fail-closed lifecycle error with a safe code only (no secret-bearing message detail).
 */
public final class LifecycleException extends RuntimeException {

    private final LifecycleErrorCode errorCode;

    public LifecycleException(LifecycleErrorCode errorCode) {
        super(errorCode == null ? "LIFECYCLE_ERROR" : errorCode.name(), null, false, false);
        this.errorCode = errorCode == null ? LifecycleErrorCode.ILLEGAL_STATE : errorCode;
    }

    public LifecycleErrorCode errorCode() {
        return errorCode;
    }
}

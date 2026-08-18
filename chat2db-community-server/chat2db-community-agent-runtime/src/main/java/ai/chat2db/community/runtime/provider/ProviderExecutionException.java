package ai.chat2db.community.runtime.provider;

public class ProviderExecutionException extends RuntimeException {

    private final ProviderFailureKind failureKind;

    public ProviderExecutionException(ProviderFailureKind failureKind, String message) {
        super(message);
        this.failureKind = failureKind;
    }

    public ProviderExecutionException(ProviderFailureKind failureKind, String message, Throwable cause) {
        super(message, cause);
        this.failureKind = failureKind;
    }

    public ProviderFailureKind getFailureKind() {
        return failureKind;
    }
}

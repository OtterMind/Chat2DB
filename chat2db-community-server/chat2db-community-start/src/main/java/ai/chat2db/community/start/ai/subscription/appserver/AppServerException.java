package ai.chat2db.community.start.ai.subscription.appserver;

/**
 * Fail-closed app-server boundary error. Messages are already redacted-safe.
 */
public class AppServerException extends RuntimeException {

    private final AppServerDisabledReason reason;

    public AppServerException(AppServerDisabledReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AppServerException(AppServerDisabledReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public AppServerDisabledReason reason() {
        return reason;
    }
}

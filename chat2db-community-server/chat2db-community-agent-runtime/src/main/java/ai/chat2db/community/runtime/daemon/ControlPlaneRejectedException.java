package ai.chat2db.community.runtime.daemon;

/**
 * A deterministic control-plane rejection. Unlike a transport failure, the
 * daemon knows that the requested mutation was not applied.
 */
final class ControlPlaneRejectedException extends ControlPlaneException {

    private final String errorCode;

    ControlPlaneRejectedException(String errorCode, String message) {
        super(errorCode + ": " + message);
        this.errorCode = errorCode;
    }

    String errorCode() {
        return errorCode;
    }
}

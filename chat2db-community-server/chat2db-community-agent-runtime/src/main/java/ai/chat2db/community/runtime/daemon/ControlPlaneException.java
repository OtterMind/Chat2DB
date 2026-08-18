package ai.chat2db.community.runtime.daemon;

public class ControlPlaneException extends RuntimeException {

    public ControlPlaneException(String message) {
        super(message);
    }

    public ControlPlaneException(String message, Throwable cause) {
        super(message, cause);
    }
}

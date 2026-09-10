package ai.chat2db.community.agent.exception.pi;

public class PiRpcException extends RuntimeException {

    public PiRpcException(String message) {
        super(message);
    }

    public PiRpcException(String message, Throwable cause) {
        super(message, cause);
    }
}

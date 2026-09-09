package ai.chat2db.community.jcef.agent;

public class PiRpcException extends RuntimeException {

    public PiRpcException(String message) {
        super(message);
    }

    public PiRpcException(String message, Throwable cause) {
        super(message, cause);
    }
}

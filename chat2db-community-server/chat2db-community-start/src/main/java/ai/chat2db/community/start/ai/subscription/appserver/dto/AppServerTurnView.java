package ai.chat2db.community.start.ai.subscription.appserver.dto;

public final class AppServerTurnView {

    private final String turnId;
    private final String threadId;
    private final String status;

    public AppServerTurnView(String turnId, String threadId, String status) {
        this.turnId = turnId;
        this.threadId = threadId;
        this.status = status;
    }

    public String turnId() {
        return turnId;
    }

    public String threadId() {
        return threadId;
    }

    public String status() {
        return status;
    }
}

package ai.chat2db.community.start.ai.subscription.appserver.dto;

public final class AppServerThreadView {

    private final String threadId;
    private final String sessionId;

    public AppServerThreadView(String threadId, String sessionId) {
        this.threadId = threadId;
        this.sessionId = sessionId;
    }

    public String threadId() {
        return threadId;
    }

    public String sessionId() {
        return sessionId;
    }
}

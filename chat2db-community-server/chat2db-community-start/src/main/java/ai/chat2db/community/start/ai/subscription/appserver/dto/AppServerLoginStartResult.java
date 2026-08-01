package ai.chat2db.community.start.ai.subscription.appserver.dto;

/**
 * Login start result without provider tokens.
 * {@code authUrl} is only the browser entry URL owned by app-server; it is never logged.
 */
public final class AppServerLoginStartResult {

    private final String type;
    private final String loginId;
    private final String authUrl;
    private final String verificationUrl;
    private final String userCode;

    public AppServerLoginStartResult(
            String type,
            String loginId,
            String authUrl,
            String verificationUrl,
            String userCode) {
        this.type = type;
        this.loginId = loginId;
        this.authUrl = authUrl;
        this.verificationUrl = verificationUrl;
        this.userCode = userCode;
    }

    public String type() {
        return type;
    }

    public String loginId() {
        return loginId;
    }

    public String authUrl() {
        return authUrl;
    }

    public String verificationUrl() {
        return verificationUrl;
    }

    public String userCode() {
        return userCode;
    }
}

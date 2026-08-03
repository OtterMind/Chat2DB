package ai.chat2db.community.start.ai.subscription.lifecycle;

/**
 * In-memory login attempt. Browser {@code authUrl} is retained only here for allowlisted
 * internal resolution and must never appear in safe DTOs or logs.
 */
final class LoginAttempt {

    private final String attemptId;
    private final LoginType loginType;
    private final String appServerLoginId;
    private final String browserAuthUrl;
    private final String verificationUrl;
    private final String userCode;
    private final long expiresAtEpochMs;
    private volatile boolean cancelled;
    private volatile boolean completed;

    LoginAttempt(
            String attemptId,
            LoginType loginType,
            String appServerLoginId,
            String browserAuthUrl,
            String verificationUrl,
            String userCode,
            long expiresAtEpochMs) {
        this.attemptId = attemptId;
        this.loginType = loginType;
        this.appServerLoginId = appServerLoginId;
        this.browserAuthUrl = browserAuthUrl;
        this.verificationUrl = verificationUrl;
        this.userCode = userCode;
        this.expiresAtEpochMs = expiresAtEpochMs;
    }

    String attemptId() {
        return attemptId;
    }

    LoginType loginType() {
        return loginType;
    }

    String appServerLoginId() {
        return appServerLoginId;
    }

    String browserAuthUrl() {
        return browserAuthUrl;
    }

    String verificationUrl() {
        return verificationUrl;
    }

    String userCode() {
        return userCode;
    }

    long expiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    boolean cancelled() {
        return cancelled;
    }

    boolean completed() {
        return completed;
    }

    void markCancelled() {
        this.cancelled = true;
    }

    void markCompleted() {
        this.completed = true;
    }

    boolean expired(long nowEpochMs) {
        return nowEpochMs > expiresAtEpochMs;
    }
}

package ai.chat2db.community.start.ai.subscription.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Safe login-start DTO for renderer/API surfaces.
 * Browser auth URLs are intentionally omitted — resolve via {@link ChatGptSubscriptionLifecycleService#resolveBrowserTarget(String)}.
 */
public final class SafeLoginStartResponse {

    private final String attemptId;
    private final LoginType loginType;
    private final long expiresAtEpochMs;
    private final String userCode;
    private final String verificationUrl;

    public SafeLoginStartResponse(
            String attemptId,
            LoginType loginType,
            long expiresAtEpochMs,
            String userCode,
            String verificationUrl) {
        this.attemptId = attemptId;
        this.loginType = loginType;
        this.expiresAtEpochMs = expiresAtEpochMs;
        this.userCode = userCode;
        this.verificationUrl = verificationUrl;
    }

    public String attemptId() {
        return attemptId;
    }

    public LoginType loginType() {
        return loginType;
    }

    public long expiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    public String userCode() {
        return userCode;
    }

    public String verificationUrl() {
        return verificationUrl;
    }

    public Map<String, Object> toSafeMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("attemptId", attemptId);
        map.put("loginType", loginType.name());
        map.put("expiresAtEpochMs", expiresAtEpochMs);
        if (userCode != null) {
            map.put("userCode", userCode);
        }
        if (verificationUrl != null) {
            map.put("verificationUrl", verificationUrl);
        }
        // Never put authUrl / tokens here.
        return map;
    }
}

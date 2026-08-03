package ai.chat2db.community.start.ai.subscription.lifecycle;

import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Safe connection status for renderer surfaces (no tokens).
 */
public final class SafeConnectionView {

    private final AiProviderConnectionState state;
    private final String maskedAccount;
    private final String discoveryErrorCode;
    private final long fenceGeneration;

    public SafeConnectionView(
            AiProviderConnectionState state,
            String maskedAccount,
            String discoveryErrorCode,
            long fenceGeneration) {
        this.state = state;
        this.maskedAccount = maskedAccount;
        this.discoveryErrorCode = discoveryErrorCode;
        this.fenceGeneration = fenceGeneration;
    }

    public AiProviderConnectionState state() {
        return state;
    }

    public String maskedAccount() {
        return maskedAccount;
    }

    public String discoveryErrorCode() {
        return discoveryErrorCode;
    }

    public long fenceGeneration() {
        return fenceGeneration;
    }

    public Map<String, Object> toSafeMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("state", state.name());
        map.put("maskedAccount", maskedAccount);
        map.put("discoveryErrorCode", discoveryErrorCode);
        map.put("fenceGeneration", fenceGeneration);
        return map;
    }
}

package ai.chat2db.community.start.ai.subscription.lifecycle;

import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Safe model availability entry. Semantics are "recently confirmed available", not permanent entitlement.
 */
public final class SafeModelAvailability {

    private final AiModelRef modelRef;
    private final String displayName;
    private final Instant discoveredAt;
    private final boolean recentlyConfirmedAvailable;
    private final boolean stale;

    public SafeModelAvailability(
            AiModelRef modelRef,
            String displayName,
            Instant discoveredAt,
            boolean recentlyConfirmedAvailable,
            boolean stale) {
        this.modelRef = modelRef;
        this.displayName = displayName;
        this.discoveredAt = discoveredAt;
        this.recentlyConfirmedAvailable = recentlyConfirmedAvailable;
        this.stale = stale;
    }

    public AiModelRef modelRef() {
        return modelRef;
    }

    public String displayName() {
        return displayName;
    }

    public Instant discoveredAt() {
        return discoveredAt;
    }

    public boolean recentlyConfirmedAvailable() {
        return recentlyConfirmedAvailable;
    }

    public boolean stale() {
        return stale;
    }

    public Map<String, Object> toSafeMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("modelId", modelRef.modelId());
        map.put("accessType", modelRef.accessType().name());
        map.put("provider", modelRef.provider().name());
        map.put("routeKind", modelRef.routeKind().name());
        map.put("displayName", displayName);
        map.put("discoveredAt", discoveredAt.toString());
        map.put("recentlyConfirmedAvailable", recentlyConfirmedAvailable);
        map.put("stale", stale);
        return map;
    }
}

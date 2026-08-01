package ai.chat2db.community.domain.api.model.ai.subscription;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record AiModelSnapshot(
        AiModelRef modelRef,
        String displayName,
        Instant discoveredAt,
        boolean available,
        String disabledReason,
        List<String> supportedReasoningEfforts,
        String defaultReasoningEffort) {

    public AiModelSnapshot {
        Objects.requireNonNull(modelRef, "modelRef");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        supportedReasoningEfforts = normalizeReasoningEfforts(supportedReasoningEfforts);
        defaultReasoningEffort = normalizeReasoningEffort(defaultReasoningEffort);
    }

    public AiModelSnapshot(AiModelRef modelRef, String displayName, Instant discoveredAt,
                           boolean available, String disabledReason) {
        this(modelRef, displayName, discoveredAt, available, disabledReason, List.of(), null);
    }

    private static List<String> normalizeReasoningEfforts(List<String> efforts) {
        if (efforts == null || efforts.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String effort : efforts) {
            String value = normalizeReasoningEffort(effort);
            if (value != null) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private static String normalizeReasoningEffort(String effort) {
        if (effort == null || effort.isBlank()) {
            return null;
        }
        String normalized = effort.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("[a-z][a-z0-9_-]{0,31}") ? normalized : null;
    }
}

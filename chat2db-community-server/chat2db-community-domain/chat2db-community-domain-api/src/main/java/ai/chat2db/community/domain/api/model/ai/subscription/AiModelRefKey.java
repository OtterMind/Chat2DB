package ai.chat2db.community.domain.api.model.ai.subscription;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;

import java.util.Optional;

/** Stable, secret-free wire representation of an {@link AiModelRef}. */
public final class AiModelRefKey {

    private static final String SEPARATOR = "::";

    private AiModelRefKey() {
    }

    public static String encode(AiModelRef ref) {
        return String.join(SEPARATOR,
                ref.accessType().name(),
                ref.provider().name(),
                ref.routeKind().name(),
                ref.modelId());
    }

    public static Optional<AiModelRef> decode(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.split(SEPARATOR, 4);
        if (parts.length != 4 || parts[3].isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AiModelRef(
                    AiAccessType.valueOf(parts[0]),
                    AiProviderEnum.valueOf(parts[1]),
                    AiRouteKind.valueOf(parts[2]),
                    parts[3]));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}

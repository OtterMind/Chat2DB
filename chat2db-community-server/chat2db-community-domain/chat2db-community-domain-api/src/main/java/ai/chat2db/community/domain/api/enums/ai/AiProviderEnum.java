package ai.chat2db.community.domain.api.enums.ai;

import java.util.Locale;

public enum AiProviderEnum {
    OPENAI,
    CLAUDE,
    GEMINI,
    MINIMAX;

    public static AiProviderEnum from(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return AiProviderEnum.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

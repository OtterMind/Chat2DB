package ai.chat2db.community.domain.api.enums.ai;

import java.util.Locale;

/** Protocol identifiers understood by Pi; contracts expose their string codes. */
public enum AiAgentModelApi {
    OPENAI_COMPLETIONS("openai-completions"),
    OPENAI_RESPONSES("openai-responses"),
    ANTHROPIC_MESSAGES("anthropic-messages"),
    GOOGLE_GENERATIVE_AI("google-generative-ai");

    private final String code;

    AiAgentModelApi(String code) { this.code = code; }

    public String getCode() { return code; }

    public static AiAgentModelApi from(String value) {
        for (AiAgentModelApi api : values()) {
            if (api.code.equals(value)) return api;
        }
        throw new IllegalArgumentException("Unsupported Agent model API: " + value);
    }

    public static AiAgentModelApi resolve(String api, String provider, String baseUrl) {
        if (api != null && !api.isBlank()) return from(api);
        return switch (provider.toUpperCase(Locale.ROOT)) {
            case "OPENAI" -> OPENAI_RESPONSES;
            case "CLAUDE" -> ANTHROPIC_MESSAGES;
            case "GEMINI" -> GOOGLE_GENERATIVE_AI;
            case "MINIMAX" -> baseUrl != null && baseUrl.toLowerCase(Locale.ROOT).contains("/anthropic")
                    ? ANTHROPIC_MESSAGES : OPENAI_COMPLETIONS;
            default -> throw new IllegalArgumentException("Unsupported Agent model provider: " + provider);
        };
    }
}

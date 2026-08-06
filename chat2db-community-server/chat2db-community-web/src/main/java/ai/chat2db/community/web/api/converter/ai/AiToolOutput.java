package ai.chat2db.community.web.api.converter.ai;

/**
 * Internal projection result passed from AI tool converters to transport adapters.
 * This is not a public tool protocol envelope.
 */
public record AiToolOutput<T>(String summary, T data) {
}

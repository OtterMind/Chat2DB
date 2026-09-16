package ai.chat2db.community.web.api.adapter.pi;

import ai.chat2db.community.web.api.model.request.agent.AgentRunContextRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** Named operation payloads shared by HTTP and desktop transports. */
public final class PiRequests {
    private PiRequests() { }
    public record Empty() { }
    public record Session(@NotBlank String sessionId) { }
    public record SessionGet(@NotBlank String sessionId, @Min(2) @Max(2) int sessionVersion) { }
    public record SessionRename(@NotBlank String sessionId, @NotBlank String title) { }
    public record RunStart(@NotBlank String sessionId, @NotBlank String modelConfigId,
            @NotBlank String message, @NotBlank String idempotencyKey, @Valid AgentRunContextRequest context) { }
    public record RunCancel(@NotBlank String sessionId, @NotBlank String runId) { }
    public record Events(@NotBlank String sessionId, @PositiveOrZero Long afterSequence,
            @Min(1) @Max(200) Integer limit) { }
    public record Decision(@NotBlank String sessionId, @NotBlank String approvalId, @NotNull Boolean approved) { }
    public record Answer(@NotBlank String sessionId, @NotBlank String questionId,
            @Size(max=64) String optionId, @Size(max=4000) String text) { }
    public record ToolEnabled(@NotBlank String toolName, @NotNull Boolean enabled) { }
    public record Output(@NotBlank String sessionId, @NotBlank String artifactId) { }
    public record OutputRead(@NotBlank String sessionId, @NotBlank String artifactId,
            String cursor, @PositiveOrZero Integer offset, @Positive Integer limit) { }
    public record OutputSearch(@NotBlank String sessionId, @NotBlank String artifactId, @NotBlank String pattern,
            Boolean literal, Boolean ignoreCase, String cursor, @Positive Integer limit) { }
}

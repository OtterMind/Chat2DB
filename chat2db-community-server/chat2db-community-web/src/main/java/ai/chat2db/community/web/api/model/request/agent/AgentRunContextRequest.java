package ai.chat2db.community.web.api.model.request.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AgentRunContextRequest(
        @Size(max = 100) String timeZone, @Valid Scope selection,
        @Valid @Size(max = 20) List<@NotNull ObjectReference> objects) {
    public record Scope(@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String dataSourceId,
            @Size(max = 256) String database, @Size(max = 256) String schema) { }
    public record ObjectReference(@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String dataSourceId,
            @Size(max = 256) String database, @Size(max = 256) String schema,
            @NotBlank @Pattern(regexp = "TABLE|VIEW") String type,
            @NotBlank @Size(max = 256) String name,
            @NotBlank @Pattern(regexp = "CURRENT_TABLE|MENTION") String source) { }
}

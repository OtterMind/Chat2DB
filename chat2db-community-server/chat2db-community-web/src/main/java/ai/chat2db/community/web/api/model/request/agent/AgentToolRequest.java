package ai.chat2db.community.web.api.model.request.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record AgentToolRequest(@NotBlank @Size(max = 200) String toolCallId,
        @NotBlank @Size(max = 100) String toolName, @NotNull Map<String, Object> arguments) { }

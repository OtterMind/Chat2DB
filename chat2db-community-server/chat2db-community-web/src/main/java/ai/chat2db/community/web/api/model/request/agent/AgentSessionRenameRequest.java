package ai.chat2db.community.web.api.model.request.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentSessionRenameRequest(@NotBlank @Size(max = 100) String title) {
}

package ai.chat2db.community.domain.api.model.request.agent;

import jakarta.validation.constraints.NotBlank;

public record AiAgentSkillResolveRequest(@NotBlank String message) { }

package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.agent.IAiAgentSkillService;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/ai/skills")
public class AiAgentSkillController {
    private final IAiAgentSkillService skills;

    public AiAgentSkillController(IAiAgentSkillService skills) {
        this.skills = skills;
    }

    @GetMapping
    public ListResult<String> list() {
        return ListResult.of(skills.prepare().stream().map(skill -> skill.name()).toList());
    }
}

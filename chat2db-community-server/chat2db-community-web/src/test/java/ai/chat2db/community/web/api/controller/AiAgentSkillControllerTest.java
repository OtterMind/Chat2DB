package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.skill.AiAgentSkill;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentSkillResolveRequest;
import ai.chat2db.community.domain.api.model.response.agent.AiAgentSkillResolveResponse;
import ai.chat2db.community.domain.api.service.agent.IAiAgentSkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiAgentSkillControllerTest {
    @Test
    void exposesCatalogNamesWithoutRuntimePaths() throws Exception {
        var controller = new AiAgentSkillController(new IAiAgentSkillService() {
            @Override public List<AiAgentSkill> prepare() {
                return List.of(new AiAgentSkill("chart", "/private/runtime/chart/SKILL.md", "version"),
                        new AiAgentSkill("query", "/private/runtime/query/SKILL.md", "version"));
            }
            @Override public AiAgentSkillResolveResponse resolve(AiAgentSkillResolveRequest request) {
                throw new UnsupportedOperationException();
            }
        });
        String result = new ObjectMapper().writeValueAsString(controller.list());
        assertEquals(List.of("chart", "query"), controller.list().getData());
        assertFalse(result.contains("/private/runtime"));
        assertFalse(result.contains("version"));
    }
}

package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.context.AiAgentRunContext;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiAgentPromptServiceImplTest {
    private final AiAgentPromptServiceImpl prompts = new AiAgentPromptServiceImpl();

    @Test
    void keepsSystemStableAndInterpolatesDataWithoutEvaluatingIt() throws Exception {
        var context = new AiAgentRunContext(new AiAgentRunContext.Environment("Asia/Shanghai", "2026-09-11T00:15:00+08:00", "CLIENT"),
                new AiAgentRunContext.Scope("123", "数据库\"${7*7}", "MYSQL", "sales", null), List.of());
        String original = "统计 orders\n${contextJson}\n<#include 'missing.ftl'>\n</user_request>";
        String system = prompts.systemPrompt();
        String rendered = prompts.userPrompt(original, context);
        assertTrue(rendered.endsWith(original + "\n</user_request>\n"));
        String contextJson = rendered.substring(rendered.indexOf('\n') + 1, rendered.indexOf("\n</chat2db_context>"));
        var parsed = new ObjectMapper().readTree(contextJson);
        assertEquals(context.selection().dataSourceName(), parsed.path("selection").path("dataSourceName").asText());
        assertEquals(context.environment().requestTime(), parsed.path("environment").path("requestTime").asText());
        assertEquals(system, prompts.systemPrompt());
        assertFalse(system.contains("2026-09-11"));
        assertFalse(system.contains("Asia/Shanghai"));
    }

    @Test
    void explicitlyRendersEmptySelectionAndRejectsMissingVariables() {
        var context = new AiAgentRunContext(new AiAgentRunContext.Environment("UTC", "2026-09-11T00:00:00Z", "UTC_FALLBACK"), null, List.of());
        String rendered = prompts.userPrompt("hello", context);
        assertTrue(rendered.contains("\"selection\" : null"));
        assertTrue(rendered.contains("\"objects\" : [ ]"));
        assertTrue(JSON.toJSONString(context).contains("\"selection\":null"));
        assertThrows(NullPointerException.class, () -> prompts.userPrompt(null, context));
        assertThrows(NullPointerException.class, () -> prompts.userPrompt("hello", null));
    }
}

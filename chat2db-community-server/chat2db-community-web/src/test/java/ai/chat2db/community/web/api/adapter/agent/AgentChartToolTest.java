package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.web.api.converter.agent.AgentChartToolConverter;
import jakarta.validation.Validation;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentChartToolTest {
    @Test
    void rejectsInventedDataWrongTypesAndInvalidNestedSeriesBeforeCallingTheDomain() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var tool = new AgentChartTool(null, new AgentChartToolConverter(), factory.getValidator());
            assertFalse(tool.execute(Map.of("resultId", "query", "chartType", "Line", "data", Map.of("amount", 999)), null).ok());
            assertFalse(tool.execute(Map.of("resultId", 123, "chartType", "Line"), null).ok());
            assertFalse(tool.execute(Map.of("resultId", "../other", "chartType", "Line"), null).ok());
            assertFalse(tool.execute(Map.of("resultId", "query", "chartType", "Combo", "series", Arrays.asList((Object) null)), null).ok());
            assertEquals("render_chart", tool.definition().name());
        }
    }
}

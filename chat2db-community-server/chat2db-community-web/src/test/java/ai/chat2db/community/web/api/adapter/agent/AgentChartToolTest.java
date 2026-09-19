package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.web.api.converter.agent.AgentChartToolConverter;
import jakarta.validation.Validation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentChartToolTest {
    @Test
    void rejectsInventedDataWrongTypesAndInvalidNestedSeriesBeforeCallingTheDomain() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var tool = new AgentChartTool(null, new AgentChartToolConverter(), factory.getValidator());
            assertFalse(tool.execute(Map.of("resultId", "query", "chartType", "Line", "data", Map.of("amount", 999)), null).ok());
            assertFalse(tool.execute(Map.of("resultId", "query", "chartType", "Line", "option", Map.of("series", List.of())), null).ok());
            assertFalse(tool.execute(Map.of("resultId", 123, "chartType", "Line"), null).ok());
            assertFalse(tool.execute(Map.of("resultId", "../other", "chartType", "Line"), null).ok());
            assertFalse(tool.execute(Map.of("resultId", "query", "chartType", "Combo", "series", Arrays.asList((Object) null)), null).ok());
            assertEquals("render_chart", tool.definition().name());
        }
    }

    @Test
    void exposesBoundedTypedGroupingAndStackingWithoutRawChartOptions() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var tool = new AgentChartTool(null, new AgentChartToolConverter(), factory.getValidator());
            var properties = (Map<?, ?>) tool.definition().parameters().get("properties");
            var groupBy = (Map<?, ?>) properties.get("groupBy");
            assertEquals("array", groupBy.get("type"));
            assertEquals(3, groupBy.get("maxItems"));
            assertEquals(true, groupBy.get("uniqueItems"));
            assertEquals("string", ((Map<?, ?>) groupBy.get("items")).get("type"));
            var stack = (Map<?, ?>) properties.get("stack");
            assertEquals("boolean", stack.get("type"));
            assertEquals(false, stack.get("default"));
            assertEquals(false, tool.definition().parameters().get("additionalProperties"));
            assertFalse(properties.containsKey("data"));
            assertFalse(properties.containsKey("option"));
        }
    }

    @Test
    void rejectsInvalidGroupAndStackTypesBeforeCallingTheDomain() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var tool = new AgentChartTool(null, new AgentChartToolConverter(), factory.getValidator());
            for (Object groups : List.of("region", 123, List.of(123), List.of(true), List.of(""), List.of(" "),
                    List.of("a", "b", "c", "d"), Arrays.asList((Object) null))) {
                var response = tool.execute(Map.of("resultId", "query", "chartType", "Column", "groupBy", groups), null);
                assertFalse(response.ok());
                assertEquals("INVALID_ARGUMENT", response.error().code());
            }
            for (Object stack : List.of("true", "false", 1, 0, List.of(true), Map.of("value", true))) {
                var response = tool.execute(Map.of("resultId", "query", "chartType", "Column", "stack", stack), null);
                assertFalse(response.ok());
                assertEquals("INVALID_ARGUMENT", response.error().code());
            }
        }
    }

    @Test
    void convertsGroupingAndStackingAndKeepsOldRequestsCompatible() {
        var converter = new AgentChartToolConverter();
        var request = converter.arguments2request(Map.of("resultId", "query", "chartType", "Column", "xField", "month",
                "yField", "amount", "groupBy", List.of("region", "channel"), "stack", true));
        assertEquals(List.of("region", "channel"), request.groupBy());
        assertTrue(request.stack());
        var legacy = converter.arguments2request(Map.of("resultId", "query", "chartType", "Line", "xField", "month", "yField", "amount"));
        assertEquals(List.of(), legacy.groupBy());
        assertFalse(legacy.stack());
    }
}

package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.chart.DbAgentQueryResult;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.*;
import ai.chat2db.community.domain.api.service.agent.IAgentOutputStorage;
import ai.chat2db.community.domain.api.service.agent.IAgentQueryResultStorage;
import ai.chat2db.community.tools.agent.tool.IAgentToolResult;
import ai.chat2db.community.tools.model.agent.tool.AgentOutputReference;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiAgentOutputServiceImplTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentToolExecutionContext context = new AgentToolExecutionContext("session", "run", "call", 1L,
            ignored -> { }, () -> true);
    private byte[] saved;
    private int saves;
    private boolean unavailable;
    private AgentOutputReference queryOutput;

    @Test
    void keepsSmallResultsUnchangedAndDoesNotTouchStorage() {
        var result = new Result(true, Map.of("answer", "small"));
        assertSame(result, service().present(result, context));
        assertEquals(0, saves);
    }

    @Test
    void storesFullResultAndPreservesOutcomePaginationAndNullValues() throws Exception {
        var data = new QueryData(List.of(new QueryColumn("value", "DECIMAL")),
                List.of(Arrays.asList(null, "9007199254740993.1200", "值😀".repeat(20000))),
                "database-text", 23L, List.of(), null);
        var result = DbAgentDatabaseResponse.success(new Scope("1", "MYSQL", "database", null), data,
                new Page(1, 50, 1, null, true, 2), null, List.of());
        var presented = service().present(result, context);
        assertTrue(presented.ok());
        assertEquals(1, saves);
        var full = mapper.readTree(saved);
        assertTrue(full.path("data").path("rows").get(0).get(0).isNull());
        assertEquals("9007199254740993.1200", full.path("data").path("rows").get(0).get(1).asText());
        assertEquals("值😀".repeat(20000), full.path("data").path("rows").get(0).get(2).asText());
        var preview = mapper.valueToTree(presented);
        assertTrue(preview.path("page").path("hasMore").asBoolean());
        assertEquals(2, preview.path("page").path("nextPage").asInt());
        assertTrue(preview.path("output").path("complete").asBoolean());
        assertTrue(mapper.writeValueAsBytes(presented).length < 10000);
        // Both serializers are used along the runtime/event path.
        assertEquals(preview.path("output").path("artifactId").asText(),
                JSON.parseObject(JSON.toJSONString(presented)).getJSONObject("output").getString("artifactId"));
    }

    @Test
    void savingFailureDoesNotTurnExecutedToolIntoFailure() {
        unavailable = true;
        var presented = service().present(new Result(true, Map.of("text", "a".repeat(40000))), context);
        assertTrue(presented.ok());
        assertEquals("unavailable", mapper.valueToTree(presented).path("output").path("mode").asText());
    }

    @Test
    void reusesQueryRowsAndMarksUpstreamClippedCellsIncomplete() {
        queryOutput = new AgentOutputReference("file", "query-output", "/session/result.jsonl", "jsonl", 80000, true, true, null);
        var rows = new QueryData(List.of(new QueryColumn("large", "TEXT")), List.of(List.of("x".repeat(40000))),
                "database-text", 1L, List.of(new CellWarning(0, 0, "clipped", 90000L, 40000L)), null);
        var statement = new SqlResult(0, "SELECT large", true, rows, new Page(1, 50, 1, null, false, null), null, "query");
        var result = DbAgentDatabaseResponse.success(null, new SqlExecutionData(List.of(statement), 1, true), null, null, List.of());
        var presented = mapper.valueToTree(service().present(result, context));
        assertEquals(0, saves);
        assertEquals("query-output", presented.path("output").path("artifactId").asText());
        assertFalse(presented.path("output").path("complete").asBoolean());
        assertEquals("query", presented.path("data").path("results").get(0).path("resultId").asText());
        assertTrue(presented.path("data").path("results").get(0).path("success").asBoolean());
    }

    private AiAgentOutputServiceImpl service() {
        IAgentOutputStorage storage = (IAgentOutputStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IAgentOutputStorage.class}, (proxy, method, args) -> {
                    if (method.getName().equals("save")) {
                        saves++;
                        var bytes = new ByteArrayOutputStream();
                        ((IAgentOutputStorage.OutputWriter) args[2]).write(bytes);
                        saved = bytes.toByteArray();
                        return unavailable ? AgentOutputReference.unavailable("disk full")
                                : new AgentOutputReference("file", "artifact", "/session/output.json", "json", saved.length, true, true, null);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        IAgentQueryResultStorage queries = new IAgentQueryResultStorage() {
            @Override public void create(DbAgentQueryResult result, Long userId) { throw new UnsupportedOperationException(); }
            @Override public DbAgentQueryResult get(String sessionId, String resultId, Long userId) { return null; }
            @Override public AgentOutputReference output(String sessionId, String resultId, Long userId) { return queryOutput; }
        };
        return new AiAgentOutputServiceImpl(storage, queries, mapper, 32768, 8192);
    }

    private record Result(boolean ok, Object data) implements IAgentToolResult<Object> { }
}

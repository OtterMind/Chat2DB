package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.domain.api.exception.ai.AiToolException;
import ai.chat2db.community.domain.api.exception.ai.AiToolInvalidArgumentException;
import ai.chat2db.community.domain.api.exception.ai.AiToolMetadataQueryException;
import ai.chat2db.community.domain.api.exception.ai.AiToolSqlConfirmationRequiredException;
import ai.chat2db.community.domain.api.exception.ai.AiToolSqlExecutionException;
import ai.chat2db.community.domain.api.model.ai.TableSchemaResult;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.SimpleTable;
import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiGetTablesSchemaRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiListTablesRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.ai.IAiToolService;
import ai.chat2db.community.web.api.converter.ai.AiToolContextConverter;
import ai.chat2db.community.web.api.converter.ai.AiToolErrorCodeMapper;
import ai.chat2db.community.web.api.converter.ai.AiToolResultConverter;
import ai.chat2db.community.web.api.converter.ai.AiToolResultSerializer;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AiToolAdapterTest {

    @ParameterizedTest
    @MethodSource("typedToolExceptions")
    void shouldMapTypedToolExceptionToStableErrorEnvelope(AiToolException exception, String expectedErrorCode) {
        AiToolAdapter adapter = adapterWith(new StubAiToolService() {
            @Override
            public List<ExecuteResponse> executeSql(AiExecuteSqlRequest request) {
                throw exception;
            }
        });

        String json = adapter.executeSql("select", null, 1L, "db", null, null);
        JSONObject result = JSON.parseObject(json);

        assertEquals(false, result.getBoolean("success"));
        assertEquals(expectedErrorCode, result.getString("errorCode"));
        assertEquals(exception.getMessage(), result.getString("summary"));
    }

    @Test
    void shouldMapPlainIllegalStateExceptionFromServiceToToolCallFailedEnvelope() {
        AiToolAdapter adapter = adapterWith(new StubAiToolService() {
            @Override
            public List<ExecuteResponse> executeSql(AiExecuteSqlRequest request) {
                throw new IllegalStateException("driver exploded");
            }
        });

        String json = adapter.executeSql("select", null, 1L, "db", null, null);
        JSONObject result = JSON.parseObject(json);

        assertEquals(false, result.getBoolean("success"));
        assertEquals("TOOL_CALL_FAILED", result.getString("errorCode"));
        assertEquals("Tool execution failed.", result.getString("summary"));
        assertFalse(result.getString("summary").contains("driver exploded"));
    }

    @Test
    void shouldSerializeConvertedStrongResultWithoutServiceJsonRoundTrip() {
        AiToolAdapter adapter = adapterWith(new StubAiToolService() {
            @Override
            public List<ExecuteResponse> executeSql(AiExecuteSqlRequest request) {
                return Collections.emptyList();
            }
        });

        String json = adapter.executeSql("select 1", null, 1L, "db", null, null);
        JSONObject result = JSON.parseObject(json);

        assertEquals(true, result.getBoolean("success"));
        assertEquals("SQL executed successfully with no result.", result.getString("summary"));
        assertEquals(0, result.getJSONObject("data").getJSONArray("results").size());
    }

    @Test
    void shouldEmitTraceContentAsSummaryNotRawJson() {
        AiToolAdapter adapter = adapterWith(new StubAiToolService() {
            @Override
            public List<ExecuteResponse> executeSql(AiExecuteSqlRequest request) {
                return Collections.emptyList();
            }
        });
        List<Map<String, Object>> tracePayloads = new ArrayList<>();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(AiChatTraceSupport.TRACE_EMITTER_KEY, (Consumer<Map<String, Object>>) tracePayloads::add);

        String json = adapter.executeSql("select 1", null, 1L, "db", null, new ToolContext(context));

        assertEquals(1, tracePayloads.size());
        Map<String, Object> payload = tracePayloads.get(0);
        assertEquals(AiChatTraceSupport.TYPE_TOOL_RESULT, payload.get("type"));
        assertEquals("execute_sql", payload.get("name"));
        assertEquals("SQL executed successfully with no result.", payload.get("content"));
        assertNotEquals(json, payload.get("content"));
        assertFalse(String.valueOf(payload.get("content")).contains("\"success\""));
        assertFalse(String.valueOf(payload.get("content")).contains("\"data\""));
    }

    private AiToolAdapter adapterWith(IAiToolService service) {
        return new AiToolAdapter(
                service,
                new AiToolContextConverter(),
                new AiToolResultConverter(),
                new AiToolResultSerializer(),
                new AiToolErrorCodeMapper());
    }

    private static Stream<Object[]> typedToolExceptions() {
        return Stream.of(
                new Object[] {new AiToolInvalidArgumentException("bad input"), "INVALID_ARGUMENT"},
                new Object[] {new AiToolSqlConfirmationRequiredException("manual confirmation required"),
                        "SQL_REQUIRES_MANUAL_CONFIRMATION"},
                new Object[] {new AiToolSqlExecutionException("SQL execution failed: syntax error"),
                        "SQL_EXECUTION_FAILED"},
                new Object[] {new AiToolMetadataQueryException("metadata query failed"),
                        "METADATA_QUERY_FAILED"});
    }

    private static class StubAiToolService implements IAiToolService {

        @Override
        public List<WorkspaceDataSource> listAllDataSources(AiToolContextRequest request) {
            return Collections.emptyList();
        }

        @Override
        public List<SimpleTable> listAllTables(AiListTablesRequest request) {
            return Collections.emptyList();
        }

        @Override
        public List<Database> listAllDatabases(Long dataSourceId, AiToolContextRequest request) {
            return Collections.emptyList();
        }

        @Override
        public List<Schema> listAllSchemas(String databaseName, Long dataSourceId, AiToolContextRequest request) {
            return Collections.emptyList();
        }

        @Override
        public List<ExecuteResponse> executeSql(AiExecuteSqlRequest request) {
            return Collections.emptyList();
        }

        @Override
        public List<TableSchemaResult> getTablesSchema(AiGetTablesSchemaRequest request) {
            return Collections.emptyList();
        }
    }
}

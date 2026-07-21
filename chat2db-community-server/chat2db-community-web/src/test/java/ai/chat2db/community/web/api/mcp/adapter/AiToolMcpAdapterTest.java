package ai.chat2db.community.web.api.mcp.adapter;

import ai.chat2db.community.domain.api.exception.ai.AiToolInvalidArgumentException;
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
import ai.chat2db.community.web.api.adapter.ai.AiChatStreamAdapter;
import ai.chat2db.community.web.api.adapter.ai.AiToolAdapter;
import ai.chat2db.community.web.api.converter.ai.AiToolContextConverter;
import ai.chat2db.community.web.api.converter.ai.AiToolErrorCodeMapper;
import ai.chat2db.community.web.api.converter.ai.AiToolResultConverter;
import ai.chat2db.community.web.api.converter.ai.AiToolResultSerializer;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolMcpAdapterTest {

    @Test
    void shouldSerializeText2SqlSuccessAsStandardJson() {
        String json = mcpAdapter(new StubAiToolService(), new StubAiChatStreamAdapter("select * from users"))
                .text2sql("list users", 1L, "app", null);

        JSONObject result = JSON.parseObject(json);

        assertEquals(true, result.getBoolean("success"));
        assertEquals("SQL generated successfully.", result.getString("summary"));
        assertEquals("select * from users", result.getJSONObject("data").getString("sql"));
        assertNull(result.getString("errorCode"));
        assertTrue(json.contains("\"errorCode\":null"));
    }

    @Test
    void shouldKeepDatabaseToolProtocolSameAsRegularAdapter() {
        IAiToolService service = new StubAiToolService() {
            @Override
            public List<ExecuteResponse> executeSql(AiExecuteSqlRequest request) {
                throw new AiToolSqlExecutionException("SQL execution failed: syntax error");
            }
        };
        AiToolAdapter regularAdapter = adapter(service);
        AiToolMcpAdapter mcpAdapter = mcpAdapter(service);

        String regular = regularAdapter.executeSql("select broken", null, 1L, "app", null, null);
        String mcp = mcpAdapter.executeSql("select broken", null, 1L, "app", null);

        assertEquals(regular, mcp);
    }

    @Test
    void shouldMapTypedServiceExceptionToStableErrorEnvelope() {
        IAiToolService service = new StubAiToolService() {
            @Override
            public List<ExecuteResponse> executeSql(AiExecuteSqlRequest request) {
                throw new AiToolInvalidArgumentException("bad mcp input");
            }
        };
        AiToolMcpAdapter mcpAdapter = mcpAdapter(service);

        String json = mcpAdapter.executeSql("select 1", null, 1L, "app", null);
        JSONObject result = JSON.parseObject(json);

        assertEquals(false, result.getBoolean("success"));
        assertEquals("bad mcp input", result.getString("summary"));
        assertEquals("INVALID_ARGUMENT", result.getString("errorCode"));
    }

    @Test
    void shouldMapPlainIllegalStateExceptionFromServiceToToolCallFailedEnvelope() {
        IAiToolService service = new StubAiToolService() {
            @Override
            public List<ExecuteResponse> executeSql(AiExecuteSqlRequest request) {
                throw new IllegalStateException("driver exploded");
            }
        };
        AiToolMcpAdapter mcpAdapter = mcpAdapter(service);

        String json = mcpAdapter.executeSql("select 1", null, 1L, "app", null);
        JSONObject result = JSON.parseObject(json);

        assertEquals(false, result.getBoolean("success"));
        assertEquals("Tool execution failed.", result.getString("summary"));
        assertEquals("TOOL_CALL_FAILED", result.getString("errorCode"));
        assertFalse(result.getString("summary").contains("driver exploded"));
    }

    private static AiToolAdapter adapter(IAiToolService service) {
        return new AiToolAdapter(
                service,
                new AiToolContextConverter(),
                new AiToolResultConverter(),
                new AiToolResultSerializer(),
                new AiToolErrorCodeMapper());
    }

    private static AiToolMcpAdapter mcpAdapter(IAiToolService service) {
        return mcpAdapter(service, null);
    }

    private static AiToolMcpAdapter mcpAdapter(IAiToolService service, AiChatStreamAdapter aiChatStreamAdapter) {
        return new AiToolMcpAdapter(
                service,
                new AiToolContextConverter(),
                new AiToolResultConverter(),
                aiChatStreamAdapter,
                new AiToolResultSerializer(),
                new AiToolErrorCodeMapper());
    }

    private static class StubAiChatStreamAdapter extends AiChatStreamAdapter {

        private final String sql;

        private StubAiChatStreamAdapter(String sql) {
            super(null, null, adapter(new StubAiToolService()), null, null, null, null, null, null);
            this.sql = sql;
        }

        @Override
        public String chatSync(ChatRequest request) {
            return sql;
        }
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

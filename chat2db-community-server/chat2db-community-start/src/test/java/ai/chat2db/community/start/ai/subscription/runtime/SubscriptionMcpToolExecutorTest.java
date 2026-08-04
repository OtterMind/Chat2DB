package ai.chat2db.community.start.ai.subscription.runtime;

import ai.chat2db.community.web.api.mcp.adapter.AiToolMcpAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SubscriptionMcpToolExecutorTest {

    @Test
    void rejectsEmptyTableNamesBeforeInvokingDatabaseTool() {
        AiToolMcpAdapter adapter = mock(AiToolMcpAdapter.class);
        SubscriptionMcpToolExecutor executor = new SubscriptionMcpToolExecutor(adapter, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> executor.execute(
                "get_tables_schema",
                "{\"tableNames\":[],\"dataSourceId\":1,\"databaseName\":\"db\"}"));

        verifyNoInteractions(adapter);
    }

    @Test
    void forwardsValidatedTableNames() throws Exception {
        AiToolMcpAdapter adapter = mock(AiToolMcpAdapter.class);
        when(adapter.getTablesSchemaStrict(List.of("orders"), 1L, "db", "public")).thenReturn("schema-ref");
        SubscriptionMcpToolExecutor executor = new SubscriptionMcpToolExecutor(adapter, new ObjectMapper());

        String result = executor.execute(
                "get_tables_schema",
                "{\"tableNames\":[\"orders\"],\"dataSourceId\":1,"
                        + "\"databaseName\":\"db\",\"schemaName\":\"public\"}");

        assertEquals("schema-ref", result);
        verify(adapter).getTablesSchemaStrict(List.of("orders"), 1L, "db", "public");
    }

    @Test
    void text2sqlDelegatesToSubscriptionModelWithoutNestedChat() throws Exception {
        AiToolMcpAdapter adapter = mock(AiToolMcpAdapter.class);
        SubscriptionMcpToolExecutor executor = new SubscriptionMcpToolExecutor(adapter, new ObjectMapper());

        String result = executor.execute(
                "text2sql",
                "{\"question\":\"daily order volume\",\"dataSourceId\":1,\"databaseName\":\"geo\"}");

        org.junit.jupiter.api.Assertions.assertTrue(
                result.startsWith("TEXT2SQL_DELEGATED_TO_SUBSCRIPTION_MODEL:"), result);
        org.junit.jupiter.api.Assertions.assertTrue(result.contains("execute_sql"), result);
        org.junit.jupiter.api.Assertions.assertTrue(result.contains("daily order volume"), result);
        verifyNoInteractions(adapter);
    }

    @Test
    void metadataToolTimeoutReturnsSoftErrorInsteadOfHanging() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode args = mapper.createObjectNode().put("dataSourceId", 9);
        String result = SubscriptionMcpToolExecutor.withMetadataTimeout(
                "list_all_databases",
                args,
                () -> {
                    Thread.sleep(SubscriptionMcpToolExecutor.METADATA_TOOL_TIMEOUT_MS + 5_000L);
                    return "should-not-return";
                });
        org.junit.jupiter.api.Assertions.assertTrue(result.startsWith("ERROR: list_all_databases timed out"), result);
        org.junit.jupiter.api.Assertions.assertTrue(result.contains("dataSourceId=9"), result);
    }
}

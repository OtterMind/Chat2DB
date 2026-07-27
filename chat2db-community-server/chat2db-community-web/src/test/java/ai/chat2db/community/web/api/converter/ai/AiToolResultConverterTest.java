package ai.chat2db.community.web.api.converter.ai;

import ai.chat2db.community.domain.api.exception.ai.AiToolSqlExecutionException;
import ai.chat2db.community.domain.api.model.ai.TableSchemaResult;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.SimpleTable;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.web.api.model.response.ai.AiSqlCellMetadataEntryPayload;
import ai.chat2db.community.web.api.model.response.ai.AiSqlResultSetPayload;
import ai.chat2db.community.web.api.model.response.ai.AiToolResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolResultConverterTest {

    @Test
    void shouldProjectSqlResultToSummaryAndTypedPayloadWithoutEnvelope() {
        ExecuteResponse response = ExecuteResponse.builder()
                .success(true)
                .build();

        AiToolOutput<List<AiSqlResultSetPayload>> output = new AiToolResultConverter().fromExecuteResult(List.of(response));
        String json = new AiToolResultSerializer().toJson(AiToolResult.success(output.summary(), output.data()));
        JSONObject payload = JSON.parseObject(json);

        assertEquals(true, payload.getBoolean("success"));
        assertNull(payload.get("tool"));
        assertEquals("SQL executed successfully with 1 result set(s).", payload.getString("summary"));
        assertEquals(1, payload.getJSONArray("data").size());
        assertNull(payload.getJSONArray("data").getJSONObject(0).get("success"));
        assertNull(payload.getJSONArray("data").getJSONObject(0).get("text"));
        assertNull(payload.getJSONArray("data").getJSONObject(0).get("results"));
        assertNull(payload.get("errorCode"));
        assertFalse(json.contains("\"errorCode\""));
    }

    @Test
    void shouldRejectNullSqlResultInsteadOfProducingInnerFailurePayload() {
        AiToolResultConverter converter = new AiToolResultConverter();

        AiToolSqlExecutionException exception = assertThrows(AiToolSqlExecutionException.class,
                () -> converter.fromExecuteResult(Collections.singletonList(null)));

        assertEquals("SQL execution returned an empty result.", exception.getMessage());
    }

    @Test
    void shouldRejectFailedSqlResultInsteadOfProducingInnerFailurePayload() {
        AiToolResultConverter converter = new AiToolResultConverter();
        ExecuteResponse failed = ExecuteResponse.builder()
                .success(false)
                .message("driver syntax error near secret")
                .build();

        AiToolSqlExecutionException exception = assertThrows(AiToolSqlExecutionException.class,
                () -> converter.fromExecuteResult(List.of(failed)));

        assertEquals("SQL execution returned a failed result.", exception.getMessage());
    }

    @Test
    void shouldSerializeDatasourceToolDataAsDocumentedArrayContract() {
        WorkspaceDataSource dataSource = new WorkspaceDataSource();
        dataSource.setId(7L);
        dataSource.setAlias("analytics");
        dataSource.setType("MYSQL");
        dataSource.setEnvType("dev");

        AiToolOutput<?> output = new AiToolResultConverter().fromDataSources(List.of(dataSource));
        String json = new AiToolResultSerializer().toJson(AiToolResult.success(output.summary(), output.data()));
        JSONObject payload = JSON.parseObject(json);
        JSONArray data = payload.getJSONArray("data");

        assertEquals(1, data.size());
        assertEquals(7L, data.getJSONObject(0).getLong("id"));
        assertEquals("analytics", data.getJSONObject(0).getString("name"));
        assertTrue(payload.get("data") instanceof JSONArray);
    }

    @Test
    void shouldSerializeAllToolPayloadsAsDocumentedArrayContract() {
        AiToolResultConverter converter = new AiToolResultConverter();
        WorkspaceDataSource dataSource = new WorkspaceDataSource();
        dataSource.setId(7L);
        dataSource.setAlias("analytics");
        dataSource.setType("MYSQL");
        dataSource.setEnvType("dev");

        assertDocumentedArrayContract(converter.fromDataSources(List.of(dataSource)));
        assertDocumentedArrayContract(converter.fromTables(List.of(SimpleTable.builder()
                .name("users")
                .tableType("BASE TABLE")
                .comment("Users")
                .build())));
        assertDocumentedArrayContract(converter.fromDatabases(List.of(Database.builder()
                .name("app")
                .comment("Application database")
                .system(false)
                .build())));
        assertDocumentedArrayContract(converter.fromSchemas(List.of(Schema.builder()
                .name("public")
                .comment("Public schema")
                .system(false)
                .build())));
        assertDocumentedArrayContract(converter.fromTableSchemas(List.of(
                new TableSchemaResult("users", "create table users(id bigint)", null))));
        assertDocumentedArrayContract(converter.fromText2Sql("select * from users"));
        assertDocumentedArrayContract(converter.fromExecuteResult(List.of(ExecuteResponse.builder()
                .success(true)
                .build())));
    }

    @Test
    void shouldProjectText2SqlResultThroughConverter() {
        AiToolOutput<?> output = new AiToolResultConverter().fromText2Sql("select * from users");
        String json = new AiToolResultSerializer().toJson(AiToolResult.success(output.summary(), output.data()));
        JSONObject payload = JSON.parseObject(json);

        assertEquals(true, payload.getBoolean("success"));
        assertEquals("SQL generated successfully.", payload.getString("summary"));
        assertEquals("select * from users", payload.getJSONArray("data").getJSONObject(0).getString("sql"));
        assertNull(payload.getString("errorCode"));
    }

    @Test
    void shouldSerializeFailedToolResultAsStandardJson() {
        String json = new AiToolResultSerializer().toJson(AiToolResult.failureWithCode(
                "INVALID_ARGUMENT",
                "sql is empty."));

        JSONObject result = JSON.parseObject(json);

        assertEquals(false, result.getBoolean("success"));
        assertNull(result.get("tool"));
        assertEquals("sql is empty.", result.getString("summary"));
        assertNull(result.get("data"));
        assertFalse(json.contains("\"data\""));
        assertEquals("INVALID_ARGUMENT", result.getString("errorCode"));
    }

    @Test
    void shouldKeepRowsPositionBasedAndPreferRawValuesWhenColumnNamesAreDuplicated() {
        List<Header> headers = List.of(
                Header.builder().name("id").build(),
                Header.builder().name("id").build(),
                Header.builder().name("note").build());
        String longText = "a".repeat(201);
        BigDecimal rawId = new BigDecimal("123.45");
        ResultCell displayFallback = ResultCell.builder()
                .value(longText)
                .rawValue(null)
                .sizeChars(201L)
                .loadedChars(201L)
                .truncated(true)
                .build();

        List<List<Object>> rows = AiSqlToolResultConverter.rowPreviewRows(
                headers,
                List.of(List.of(
                        ResultCell.builder().value("123.45").rawValue(rawId).build(),
                        ResultCell.of(null),
                        displayFallback)));
        List<AiSqlCellMetadataEntryPayload> metadata = AiSqlToolResultConverter.cellMetadataEntries(
                headers,
                List.of(List.of(
                        ResultCell.builder().value("123.45").rawValue(rawId).build(),
                        ResultCell.of(null),
                        displayFallback)));

        assertEquals(List.of("id", "id", "note"), AiSqlToolResultConverter.columnNames(headers));
        assertEquals(1, rows.size());
        assertEquals(rawId, rows.get(0).get(0));
        assertNull(rows.get(0).get(1));
        assertEquals(longText, rows.get(0).get(2));
        assertEquals(1, metadata.size());
        assertEquals(0, metadata.get(0).getRowIndex());
        assertEquals(2, metadata.get(0).getColumnIndex());
        assertEquals(false, metadata.get(0).getRawValueAvailable());
        assertEquals(true, metadata.get(0).getTruncated());
        assertEquals(201L, metadata.get(0).getSizeChars());
    }

    @Test
    void shouldTreatStringOnlyResultCellAsRawPreserving() {
        ExecuteResponse response = ExecuteResponse.builder()
                .success(true)
                .headerList(List.of(Header.builder().name("key").build()))
                .dataList(List.of(List.of(ResultCell.of("redis-value"))))
                .build();

        AiToolOutput<List<AiSqlResultSetPayload>> output = new AiToolResultConverter().fromExecuteResult(List.of(response));
        AiSqlResultSetPayload resultSet = output.data().get(0);

        assertEquals("redis-value", resultSet.getRows().get(0).get(0));
        assertTrue(resultSet.getCellMetadata().isEmpty());
    }

    @Test
    void shouldFallbackToDisplayPreviewForTransportUnsafeRawValues() throws Exception {
        List<Header> headers = List.of(
                Header.builder().name("clob_col").build(),
                Header.builder().name("blob_col").build(),
                Header.builder().name("bytes_col").build(),
                Header.builder().name("reader_col").build(),
                Header.builder().name("stream_col").build(),
                Header.builder().name("large_col").build(),
                Header.builder().name("truncated_col").build(),
                Header.builder().name("driver_col").build());
        Clob clob = new SerialClob("large text".toCharArray());
        Blob blob = new SerialBlob(new byte[] {1, 2, 3});
        DriverSpecificValue driverValue = new DriverSpecificValue("POINT(1 2)");
        List<ResultCell> row = List.of(
                ResultCell.builder().value("clob preview").rawValue(clob).valueType("TEXT").sizeChars(10L).build(),
                ResultCell.builder().value("blob preview").rawValue(blob).valueType("BINARY").sizeBytes(3L).build(),
                ResultCell.builder().value("bytes preview").rawValue(new byte[] {1, 2, 3}).valueType("BINARY").sizeBytes(3L).build(),
                ResultCell.builder().value("reader preview").rawValue(new StringReader("abc")).valueType("TEXT").build(),
                ResultCell.builder().value("stream preview").rawValue(new ByteArrayInputStream(new byte[] {1})).valueType("BINARY").build(),
                ResultCell.builder().value("large preview").rawValue("raw but large").largeValue(true).valueType("TEXT").sizeChars(20L).build(),
                ResultCell.builder().value("truncated preview").rawValue("raw but truncated").truncated(true).valueType("TEXT").loadedChars(9L).sizeChars(20L).build(),
                ResultCell.builder().value("driver preview").rawValue(driverValue).valueType("GEOMETRY").build());

        List<List<Object>> rows = AiSqlToolResultConverter.rowPreviewRows(headers, List.of(row));
        List<AiSqlCellMetadataEntryPayload> metadata = AiSqlToolResultConverter.cellMetadataEntries(headers, List.of(row));

        assertEquals(List.of(
                "clob preview",
                "blob preview",
                "bytes preview",
                "reader preview",
                "stream preview",
                "large preview",
                "truncated preview",
                "driver preview"), rows.get(0));
        assertEquals(8, metadata.size());
        assertEquals(0, metadata.get(0).getRowIndex());
        assertEquals(0, metadata.get(0).getColumnIndex());
        assertEquals(false, metadata.get(0).getRawValueAvailable());
        assertEquals("UNSAFE_RAW_VALUE:javax.sql.rowset.serial.SerialClob",
                metadata.get(0).getRawValueUnavailableReason());
        assertEquals(1, metadata.get(1).getColumnIndex());
        assertEquals("UNSAFE_RAW_VALUE:javax.sql.rowset.serial.SerialBlob",
                metadata.get(1).getRawValueUnavailableReason());
        assertEquals(2, metadata.get(2).getColumnIndex());
        assertEquals("UNSAFE_RAW_VALUE:[B", metadata.get(2).getRawValueUnavailableReason());
        assertEquals(5, metadata.get(5).getColumnIndex());
        assertEquals("LARGE_VALUE", metadata.get(5).getRawValueUnavailableReason());
        assertEquals(6, metadata.get(6).getColumnIndex());
        assertEquals("TRUNCATED_VALUE", metadata.get(6).getRawValueUnavailableReason());
        assertEquals(true, metadata.get(6).getTruncated());
        assertEquals(20L, metadata.get(6).getSizeChars());
        assertEquals(9L, metadata.get(6).getLoadedChars());
        assertEquals(7, metadata.get(7).getColumnIndex());
        assertEquals(false, metadata.get(7).getRawValueAvailable());
        assertEquals("UNSAFE_RAW_VALUE:" + DriverSpecificValue.class.getName(),
                metadata.get(7).getRawValueUnavailableReason());
    }

    @Test
    void shouldSerializeLargeJdbcRawValuesAsPreviewAndMetadata() throws Exception {
        String clobPreview = "[CLOB] 20.00 MB preview";
        String blobPreview = "[BLOB] 5.00 MB preview";
        Clob clob = new SerialClob("large text".toCharArray());
        Blob blob = new SerialBlob(new byte[] {1, 2, 3});
        ExecuteResponse response = ExecuteResponse.builder()
                .success(true)
                .headerList(List.of(
                        Header.builder().name("content").build(),
                        Header.builder().name("payload").build()))
                .dataList(List.of(List.of(
                        ResultCell.builder()
                                .value(clobPreview)
                                .rawValue(clob)
                                .largeValue(true)
                                .truncated(true)
                                .valueType("TEXT")
                                .sizeChars(20L * 1024L * 1024L)
                                .loadedChars(2048L)
                                .build(),
                        ResultCell.builder()
                                .value(blobPreview)
                                .rawValue(blob)
                                .largeValue(true)
                                .truncated(true)
                                .valueType("BINARY")
                                .sizeBytes(5L * 1024L * 1024L)
                                .loadedBytes(1024L)
                                .build())))
                .build();

        AiToolOutput<List<AiSqlResultSetPayload>> output = new AiToolResultConverter().fromExecuteResult(List.of(response));
        String json = new AiToolResultSerializer().toJson(AiToolResult.success(output.summary(), output.data()));
        JSONObject payload = JSON.parseObject(json);
        JSONObject resultSet = payload.getJSONArray("data").getJSONObject(0);
        JSONArray rows = resultSet.getJSONArray("rows");
        JSONArray metadata = resultSet.getJSONArray("cellMetadata");

        assertFalse(json.contains("\"rawValue\""));
        assertFalse(json.contains("\"text\""));
        assertFalse(json.contains("\"message\""));
        assertFalse(json.contains("\"description\""));
        assertFalse(json.contains("\"rowCellMetadata\""));
        assertFalse(json.contains("\":null"));
        assertEquals(clobPreview, rows.getJSONArray(0).getString(0));
        assertEquals(blobPreview, rows.getJSONArray(0).getString(1));
        assertEquals(2, metadata.size());
        assertEquals(0, metadata.getJSONObject(0).getInteger("rowIndex"));
        assertEquals(0, metadata.getJSONObject(0).getInteger("columnIndex"));
        assertEquals(false, metadata.getJSONObject(0).getBoolean("rawValueAvailable"));
        assertEquals(true, metadata.getJSONObject(0).getBoolean("largeValue"));
        assertEquals(true, metadata.getJSONObject(0).getBoolean("truncated"));
        assertEquals("TEXT", metadata.getJSONObject(0).getString("valueType"));
        assertEquals(20L * 1024L * 1024L, metadata.getJSONObject(0).getLong("sizeChars"));
        assertEquals(2048L, metadata.getJSONObject(0).getLong("loadedChars"));
        assertEquals("LARGE_VALUE", metadata.getJSONObject(0).getString("rawValueUnavailableReason"));
        assertEquals(1, metadata.getJSONObject(1).getInteger("columnIndex"));
        assertEquals(false, metadata.getJSONObject(1).getBoolean("rawValueAvailable"));
        assertEquals("BINARY", metadata.getJSONObject(1).getString("valueType"));
        assertEquals(5L * 1024L * 1024L, metadata.getJSONObject(1).getLong("sizeBytes"));
        assertEquals(1024L, metadata.getJSONObject(1).getLong("loadedBytes"));
        assertEquals("LARGE_VALUE", metadata.getJSONObject(1).getString("rawValueUnavailableReason"));
    }

    @Test
    void shouldOnlyExposeWhitelistedJsonSafeRawValues() {
        Instant instant = Instant.parse("2026-07-21T10:15:30Z");
        Map<String, Object> jsonObject = new LinkedHashMap<>();
        jsonObject.put("date", LocalDate.of(2026, 7, 21));
        jsonObject.put("values", List.of("ok", 12, true));
        DriverSpecificValue driverValue = new DriverSpecificValue("POINT(1 2)");
        ExecuteResponse response = ExecuteResponse.builder()
                .success(true)
                .headerList(List.of(
                        Header.builder().name("amount").build(),
                        Header.builder().name("created_at").build(),
                        Header.builder().name("json_doc").build(),
                        Header.builder().name("geometry").build()))
                .dataList(List.of(List.of(
                        ResultCell.builder().value("123.45").rawValue(new BigDecimal("123.45")).build(),
                        ResultCell.builder().value("2026-07-21 10:15:30").rawValue(Timestamp.from(instant)).build(),
                        ResultCell.builder().value("{...}").rawValue(jsonObject).build(),
                        ResultCell.builder().value("POINT(1 2)").rawValue(driverValue).build())))
                .build();

        AiToolOutput<List<AiSqlResultSetPayload>> output = new AiToolResultConverter().fromExecuteResult(List.of(response));
        String json = new AiToolResultSerializer().toJson(AiToolResult.success(output.summary(), output.data()));
        JSONObject resultSet = JSON.parseObject(json).getJSONArray("data").getJSONObject(0);
        JSONArray row = resultSet.getJSONArray("rows").getJSONArray(0);
        JSONArray metadata = resultSet.getJSONArray("cellMetadata");

        assertEquals(new BigDecimal("123.45"), row.getBigDecimal(0));
        assertEquals("2026-07-21T10:15:30Z", row.getString(1));
        assertEquals("2026-07-21", row.getJSONObject(2).getString("date"));
        assertEquals("ok", row.getJSONObject(2).getJSONArray("values").getString(0));
        assertEquals(12, row.getJSONObject(2).getJSONArray("values").getInteger(1));
        assertEquals(true, row.getJSONObject(2).getJSONArray("values").getBoolean(2));
        assertEquals("POINT(1 2)", row.getString(3));
        assertEquals(1, metadata.size());
        assertEquals(0, metadata.getJSONObject(0).getInteger("rowIndex"));
        assertEquals(3, metadata.getJSONObject(0).getInteger("columnIndex"));
        assertEquals(false, metadata.getJSONObject(0).getBoolean("rawValueAvailable"));
        assertEquals("UNSAFE_RAW_VALUE:" + DriverSpecificValue.class.getName(),
                metadata.getJSONObject(0).getString("rawValueUnavailableReason"));
    }

    @Test
    void shouldExposeRowPreviewTruncationMetadata() {
        Header header = Header.builder().name("id").build();
        List<List<ResultCell>> rows = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            rows.add(List.of(ResultCell.of(String.valueOf(i))));
        }
        ExecuteResponse response = ExecuteResponse.builder()
                .success(true)
                .headerList(List.of(header))
                .dataList(rows)
                .build();

        AiSqlResultSetPayload result = AiSqlToolResultConverter.executeResponseData(1, response);

        assertEquals(51, result.getRowCount());
        assertEquals(50, result.getPreviewRowCount());
        assertEquals(true, result.getRowsTruncated());
        assertEquals(50, result.getRows().size());
    }

    private static void assertDocumentedArrayContract(AiToolOutput<?> output) {
        String json = new AiToolResultSerializer().toJson(AiToolResult.success(output.summary(), output.data()));
        JSONObject payload = JSON.parseObject(json);

        assertTrue(payload.get("data") instanceof JSONArray);
        assertFalse(json.contains("\"data\":{\"datasources\""));
        assertFalse(json.contains("\"data\":{\"tables\""));
        assertFalse(json.contains("\"data\":{\"databases\""));
        assertFalse(json.contains("\"data\":{\"schemas\""));
        assertFalse(json.contains("\"data\":{\"results\""));
        assertFalse(json.contains("\"data\":{\"sql\""));
    }

    private record DriverSpecificValue(String value) {
    }
}

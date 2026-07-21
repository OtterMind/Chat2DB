package ai.chat2db.community.web.api.converter.ai;

import ai.chat2db.community.domain.api.model.ai.AiToolResult;
import ai.chat2db.community.domain.api.model.ai.SqlToolData;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolResultConverterTest {

    @Test
    void shouldProjectSqlResultToSummaryAndTypedPayloadWithoutEnvelope() {
        ExecuteResponse response = ExecuteResponse.builder()
                .success(true)
                .build();

        AiToolOutput<SqlToolData> output = new AiToolResultConverter().fromExecuteResult(List.of(response));
        String json = new AiToolResultSerializer().toJson(AiToolResult.success(output.summary(), output.data()));
        JSONObject payload = JSON.parseObject(json);

        assertEquals(true, payload.getBoolean("success"));
        assertNull(payload.get("tool"));
        assertEquals("SQL executed successfully with 1 result set(s).", payload.getString("summary"));
        assertEquals(1, payload.getJSONObject("data").getJSONArray("results").size());
        assertNull(payload.get("errorCode"));
        assertTrue(json.contains("\"errorCode\":null"));
    }

    @Test
    void shouldProjectText2SqlResultThroughConverter() {
        AiToolOutput<?> output = new AiToolResultConverter().fromText2Sql("select * from users");
        String json = new AiToolResultSerializer().toJson(AiToolResult.success(output.summary(), output.data()));
        JSONObject payload = JSON.parseObject(json);

        assertEquals(true, payload.getBoolean("success"));
        assertEquals("SQL generated successfully.", payload.getString("summary"));
        assertEquals("select * from users", payload.getJSONObject("data").getString("sql"));
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

        List<List<Object>> rows = AiToolResultConverter.rowPreviewRows(
                headers,
                List.of(List.of(
                        ResultCell.builder().value("123.45").rawValue(rawId).build(),
                        ResultCell.of(null),
                        displayFallback)));
        List<List<SqlToolData.CellMetadata>> metadata = AiToolResultConverter.rowPreviewCellMetadata(
                headers,
                List.of(List.of(
                        ResultCell.builder().value("123.45").rawValue(rawId).build(),
                        ResultCell.of(null),
                        displayFallback)));

        assertEquals(List.of("id", "id", "note"), AiToolResultConverter.columnNames(headers));
        assertEquals(1, rows.size());
        assertEquals(rawId, rows.get(0).get(0));
        assertNull(rows.get(0).get(1));
        assertEquals(longText, rows.get(0).get(2));
        assertNull(metadata.get(0).get(0));
        assertNull(metadata.get(0).get(1));
        assertEquals(false, metadata.get(0).get(2).getRawValueAvailable());
        assertEquals(true, metadata.get(0).get(2).getTruncated());
        assertEquals(201L, metadata.get(0).get(2).getSizeChars());
    }

    @Test
    void shouldTreatStringOnlyResultCellAsRawPreserving() {
        ExecuteResponse response = ExecuteResponse.builder()
                .success(true)
                .headerList(List.of(Header.builder().name("key").build()))
                .dataList(List.of(List.of(ResultCell.of("redis-value"))))
                .build();

        AiToolOutput<SqlToolData> output = new AiToolResultConverter().fromExecuteResult(List.of(response));
        SqlToolData.ResultSet resultSet = output.data().getResults().get(0);

        assertEquals("redis-value", resultSet.getRows().get(0).get(0));
        assertNull(resultSet.getRowCellMetadata().get(0).get(0));
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

        List<List<Object>> rows = AiToolResultConverter.rowPreviewRows(headers, List.of(row));
        List<List<SqlToolData.CellMetadata>> metadata = AiToolResultConverter.rowPreviewCellMetadata(headers, List.of(row));

        assertEquals(List.of(
                "clob preview",
                "blob preview",
                "bytes preview",
                "reader preview",
                "stream preview",
                "large preview",
                "truncated preview",
                "driver preview"), rows.get(0));
        assertEquals(false, metadata.get(0).get(0).getRawValueAvailable());
        assertEquals("UNSAFE_RAW_VALUE:javax.sql.rowset.serial.SerialClob",
                metadata.get(0).get(0).getRawValueUnavailableReason());
        assertEquals("UNSAFE_RAW_VALUE:javax.sql.rowset.serial.SerialBlob",
                metadata.get(0).get(1).getRawValueUnavailableReason());
        assertEquals("UNSAFE_RAW_VALUE:[B", metadata.get(0).get(2).getRawValueUnavailableReason());
        assertEquals("LARGE_VALUE", metadata.get(0).get(5).getRawValueUnavailableReason());
        assertEquals("TRUNCATED_VALUE", metadata.get(0).get(6).getRawValueUnavailableReason());
        assertEquals(true, metadata.get(0).get(6).getTruncated());
        assertEquals(20L, metadata.get(0).get(6).getSizeChars());
        assertEquals(9L, metadata.get(0).get(6).getLoadedChars());
        assertEquals(false, metadata.get(0).get(7).getRawValueAvailable());
        assertEquals("UNSAFE_RAW_VALUE:" + DriverSpecificValue.class.getName(),
                metadata.get(0).get(7).getRawValueUnavailableReason());
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

        AiToolOutput<SqlToolData> output = new AiToolResultConverter().fromExecuteResult(List.of(response));
        String json = new AiToolResultSerializer().toJson(AiToolResult.success(output.summary(), output.data()));
        JSONObject payload = JSON.parseObject(json);
        JSONObject resultSet = payload.getJSONObject("data").getJSONArray("results").getJSONObject(0);
        JSONArray rows = resultSet.getJSONArray("rows");
        JSONArray metadata = resultSet.getJSONArray("rowCellMetadata");

        assertFalse(json.contains("\"rawValue\""));
        assertEquals(clobPreview, rows.getJSONArray(0).getString(0));
        assertEquals(blobPreview, rows.getJSONArray(0).getString(1));
        assertEquals(false, metadata.getJSONArray(0).getJSONObject(0).getBoolean("rawValueAvailable"));
        assertEquals(true, metadata.getJSONArray(0).getJSONObject(0).getBoolean("largeValue"));
        assertEquals(true, metadata.getJSONArray(0).getJSONObject(0).getBoolean("truncated"));
        assertEquals("TEXT", metadata.getJSONArray(0).getJSONObject(0).getString("valueType"));
        assertEquals(20L * 1024L * 1024L, metadata.getJSONArray(0).getJSONObject(0).getLong("sizeChars"));
        assertEquals(2048L, metadata.getJSONArray(0).getJSONObject(0).getLong("loadedChars"));
        assertEquals("LARGE_VALUE", metadata.getJSONArray(0).getJSONObject(0).getString("rawValueUnavailableReason"));
        assertEquals(false, metadata.getJSONArray(0).getJSONObject(1).getBoolean("rawValueAvailable"));
        assertEquals("BINARY", metadata.getJSONArray(0).getJSONObject(1).getString("valueType"));
        assertEquals(5L * 1024L * 1024L, metadata.getJSONArray(0).getJSONObject(1).getLong("sizeBytes"));
        assertEquals(1024L, metadata.getJSONArray(0).getJSONObject(1).getLong("loadedBytes"));
        assertEquals("LARGE_VALUE", metadata.getJSONArray(0).getJSONObject(1).getString("rawValueUnavailableReason"));
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

        AiToolOutput<SqlToolData> output = new AiToolResultConverter().fromExecuteResult(List.of(response));
        String json = new AiToolResultSerializer().toJson(AiToolResult.success(output.summary(), output.data()));
        JSONObject resultSet = JSON.parseObject(json).getJSONObject("data").getJSONArray("results").getJSONObject(0);
        JSONArray row = resultSet.getJSONArray("rows").getJSONArray(0);
        JSONArray metadata = resultSet.getJSONArray("rowCellMetadata").getJSONArray(0);

        assertEquals(new BigDecimal("123.45"), row.getBigDecimal(0));
        assertEquals("2026-07-21T10:15:30Z", row.getString(1));
        assertEquals("2026-07-21", row.getJSONObject(2).getString("date"));
        assertEquals("ok", row.getJSONObject(2).getJSONArray("values").getString(0));
        assertEquals(12, row.getJSONObject(2).getJSONArray("values").getInteger(1));
        assertEquals(true, row.getJSONObject(2).getJSONArray("values").getBoolean(2));
        assertEquals("POINT(1 2)", row.getString(3));
        assertNull(metadata.get(0));
        assertNull(metadata.get(1));
        assertNull(metadata.get(2));
        assertEquals(false, metadata.getJSONObject(3).getBoolean("rawValueAvailable"));
        assertEquals("UNSAFE_RAW_VALUE:" + DriverSpecificValue.class.getName(),
                metadata.getJSONObject(3).getString("rawValueUnavailableReason"));
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

        SqlToolData.ResultSet result = AiToolResultConverter.executeResponseData(1, response);

        assertEquals(51, result.getRowCount());
        assertEquals(50, result.getPreviewRowCount());
        assertEquals(true, result.getRowsTruncated());
        assertEquals(50, result.getRows().size());
    }

    private record DriverSpecificValue(String value) {
    }
}

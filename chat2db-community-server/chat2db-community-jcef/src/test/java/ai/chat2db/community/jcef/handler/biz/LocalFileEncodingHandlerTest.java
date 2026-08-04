package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LocalFileEncodingHandlerTest {

    private static final Charset GB18030 = Charset.forName("GB18030");

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnAndReuseLocalFileEncodingMetadata() throws Exception {
        Path file = tempDir.resolve("windows.sql");
        String original = "select '\u4e2d\u6587';";
        String updated = "select '\u4fee\u6539\u540e';";
        Files.write(file, original.getBytes(GB18030));
        AtomicReference<String> readResponse = new AtomicReference<>();

        new ReadFileHandler().handle(
                message(Map.of("path", file.toString())),
                new ConsoleResult(),
                callback(readResponse)
        );

        JSONObject data = JSON.parseObject(readResponse.get()).getJSONObject("data");
        assertEquals(original, data.getString("content"));
        assertEquals("GB18030", data.getString("charset"));
        assertEquals(false, data.getBooleanValue("bom"));

        AtomicReference<String> updateResponse = new AtomicReference<>();
        new UpdateFileContentHandler().handle(
                message(Map.of(
                        "filePath", file.toString(),
                        "fileContent", updated,
                        "charset", data.getString("charset"),
                        "bom", data.getBooleanValue("bom")
                )),
                new ConsoleResult(),
                callback(updateResponse)
        );

        assertEquals(true, JSON.parseObject(updateResponse.get()).getBooleanValue("data"));
        assertArrayEquals(updated.getBytes(GB18030), Files.readAllBytes(file));
    }

    @Test
    void shouldFailWithoutReplacingFileWhenContentCannotUseRequestedCharset() throws Exception {
        Path file = tempDir.resolve("ascii.sql");
        byte[] original = "select 'original';".getBytes(Charset.forName("US-ASCII"));
        Files.write(file, original);
        AtomicReference<String> successResponse = new AtomicReference<>();
        AtomicReference<String> failureMessage = new AtomicReference<>();
        AtomicInteger failureCode = new AtomicInteger();

        new UpdateFileContentHandler().handle(
                message(Map.of(
                        "filePath", file.toString(),
                        "fileContent", "select '\u4e2d\u6587';",
                        "charset", "US-ASCII",
                        "bom", false
                )),
                new ConsoleResult(),
                new CefQueryCallback() {
                    @Override
                    public void success(String value) {
                        successResponse.set(value);
                    }

                    @Override
                    public void failure(int errorCode, String errorMessage) {
                        failureCode.set(errorCode);
                        failureMessage.set(errorMessage);
                    }
                }
        );

        assertNull(successResponse.get());
        assertEquals(500, failureCode.get());
        assertNotNull(failureMessage.get());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void shouldDetectExistingEncodingWhenWorkspaceMetadataIsMissing() throws Exception {
        Path file = tempDir.resolve("restored-workspace.sql");
        String updated = "select '\u6062\u590d\u7684\u5de5\u4f5c\u533a';";
        Files.write(file, "select '\u539f\u6587\u4ef6';".getBytes(GB18030));
        AtomicReference<String> response = new AtomicReference<>();

        new UpdateFileContentHandler().handle(
                message(Map.of("filePath", file.toString(), "fileContent", updated)),
                new ConsoleResult(),
                callback(response)
        );

        assertEquals(true, JSON.parseObject(response.get()).getBooleanValue("data"));
        assertArrayEquals(updated.getBytes(GB18030), Files.readAllBytes(file));
    }

    private ConsoleMessage message(Map<String, Object> payload) {
        ConsoleMessage message = new ConsoleMessage();
        message.setMessage(JSON.toJSONString(payload));
        return message;
    }

    private CefQueryCallback callback(AtomicReference<String> response) {
        return new CefQueryCallback() {
            @Override
            public void success(String value) {
                response.set(value);
            }

            @Override
            public void failure(int errorCode, String errorMessage) {
                throw new AssertionError(errorCode + ": " + errorMessage);
            }
        };
    }
}

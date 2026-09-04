package ai.chat2db.community.jcef.handler.biz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SelectTlsFileContentHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void readsPemTextWithoutReturningLocalPath() throws Exception {
        Path pem = tempDir.resolve("ca.pem");
        String content = "test-pem-content";
        Files.writeString(pem, content, StandardCharsets.UTF_8);

        Map<String, Object> result = SelectTlsFileContentHandler.readTlsFile(pem, "text", 1024);

        assertEquals("ca.pem", result.get("fileName"));
        assertEquals(content, result.get("content"));
        assertFalse(result.containsKey("path"));
        assertFalse(result.containsKey("filePath"));
    }

    @Test
    void readsBinaryStoreAsBase64WithoutReturningLocalPath() throws Exception {
        Path store = tempDir.resolve("client.p12");
        byte[] bytes = new byte[]{0, 1, 2, 3, 4};
        Files.write(store, bytes);

        Map<String, Object> result = SelectTlsFileContentHandler.readTlsFile(store, "base64", 1024);

        assertEquals("client.p12", result.get("fileName"));
        assertEquals(Base64.getEncoder().encodeToString(bytes), result.get("content"));
        assertFalse(result.containsKey("path"));
        assertFalse(result.containsKey("filePath"));
    }

    @Test
    void normalizesSelectedPathBeforeReading() throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("nested"));
        Path pem = tempDir.resolve("ca.pem");
        Files.writeString(pem, "certificate", StandardCharsets.UTF_8);

        Map<String, Object> result = SelectTlsFileContentHandler.readTlsFile(
                nested.resolve("..").resolve("ca.pem"), "text", 1024);

        assertEquals("ca.pem", result.get("fileName"));
        assertEquals("certificate", result.get("content"));
    }

    @Test
    void rejectsDirectoryWithGenericPathFreeError() {
        Exception exception = assertThrows(Exception.class,
                () -> SelectTlsFileContentHandler.readTlsFile(tempDir, "text", 1024));

        assertGenericErrorWithoutPath(exception);
    }

    @Test
    void rejectsDisallowedExtensionWithGenericPathFreeError() throws Exception {
        Path disallowed = tempDir.resolve("secret-location.txt");
        Files.writeString(disallowed, "not tls", StandardCharsets.UTF_8);

        Exception exception = assertThrows(Exception.class,
                () -> SelectTlsFileContentHandler.readTlsFile(disallowed, "text", 1024));

        assertGenericErrorWithoutPath(exception);
    }

    @Test
    void rejectsOversizeFileBeforeReturningContent() throws Exception {
        Path pem = tempDir.resolve("oversize.pem");
        Files.write(pem, new byte[]{1, 2, 3, 4, 5});

        Exception exception = assertThrows(Exception.class,
                () -> SelectTlsFileContentHandler.readTlsFile(pem, "text", 4));

        assertGenericErrorWithoutPath(exception);
    }

    private void assertGenericErrorWithoutPath(Exception exception) {
        assertEquals("Unable to read selected TLS file.", exception.getMessage());
        assertFalse(exception.getMessage().contains(tempDir.toString()));
        assertFalse(exception.getMessage().contains("secret-location"));
    }
}

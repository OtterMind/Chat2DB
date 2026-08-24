package ai.chat2db.community.tools.util;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcJarUtilsTest {

    private final List<File> filesToDelete = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void cleanUp() {
        if (server != null) {
            server.stop(0);
        }
        filesToDelete.forEach(File::delete);
    }

    @Test
    void asyncUntrustedDownloadHostIsRejectedWithoutLeavingAPartialFile() throws Exception {
        String fileName = uniqueFileName();
        File output = outputFile(fileName);

        IOException failure = assertThrows(IOException.class, () -> JdbcJarUtils.asyncDownload(
            "http://user:password@127.0.0.1:1/" + fileName + "?token=secret",
            ignored -> { }));

        assertTrue(failure.getMessage().contains("untrusted download host"));
        assertFalse(failure.getMessage().contains("user:password"));
        assertFalse(failure.getMessage().contains("token=secret"));
        assertFalse(output.exists());
    }

    @Test
    void asyncUntrustedHttpFailureIsReportedWithoutLeavingAPartialFile() throws Exception {
        String fileName = uniqueFileName();
        File output = outputFile(fileName);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/" + fileName, exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        IOException failure = assertThrows(IOException.class, () -> JdbcJarUtils.asyncDownload(
            "http://user:password@127.0.0.1:" + server.getAddress().getPort()
                + "/" + fileName + "?token=secret",
            ignored -> { }));

        assertTrue(failure.getMessage().contains("untrusted download host"));
        assertFalse(failure.getMessage().contains("user:password"));
        assertFalse(failure.getMessage().contains("token=secret"));
        assertFalse(output.exists());
    }

    @Test
    void synchronousUntrustedDownloadHostUsesSanitizedUrl() throws Exception {
        String fileName = uniqueFileName();
        File output = outputFile(fileName);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/" + fileName, exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        String url = "http://user:password@127.0.0.1:" + server.getAddress().getPort()
            + "/" + fileName + "?token=secret";

        IOException failure = assertThrows(IOException.class, () -> JdbcJarUtils.download(url));

        assertTrue(failure.getMessage().contains("untrusted download host"));
        assertFalse(failure.getMessage().contains("user:password"));
        assertFalse(failure.getMessage().contains("token=secret"));
        assertFalse(output.exists());
    }

    @Test
    void sanitizeUrlRemovesUserInfoQueryAndFragment() {
        String sanitized = JdbcJarUtils.sanitizeUrl(
            "https://user:password@example.com:8443/path/driver.jar?token=secret#fragment");
        assertEquals("https://example.com:8443/path/driver.jar", sanitized);
        assertFalse(sanitized.contains("user:password"));
        assertFalse(sanitized.contains("token=secret"));
    }

    @Test
    void driverFileRejectsPathTraversalAndNestedPaths() {
        assertThrows(IOException.class, () -> JdbcJarUtils.driverFile("../driver.jar"));
        assertThrows(IOException.class, () -> JdbcJarUtils.driverFile("nested/driver.jar"));
        assertThrows(IOException.class, () -> JdbcJarUtils.driverFile("nested\\driver.jar"));
    }

    @Test
    void driverFileAcceptsOnlyJarAndZipArtifacts() throws IOException {
        assertThrows(IOException.class, () -> JdbcJarUtils.driverFile("driver.txt"));
        assertEquals(outputFile("driver.jar").getAbsolutePath(), JdbcJarUtils.driverFile("driver.jar").getAbsolutePath());
        assertEquals(outputFile("driver.zip").getAbsolutePath(), JdbcJarUtils.driverFile("driver.zip").getAbsolutePath());
    }

    private String uniqueFileName() {
        return "jdbc-driver-test-" + UUID.randomUUID() + ".jar";
    }

    private File outputFile(String fileName) {
        File output = new File(JdbcDriverConstants.DRIVER_LIB_PATH, fileName);
        filesToDelete.add(output);
        return output;
    }
}

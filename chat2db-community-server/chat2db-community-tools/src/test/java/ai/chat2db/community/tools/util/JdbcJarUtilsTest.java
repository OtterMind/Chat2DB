package ai.chat2db.community.tools.util;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    void asyncConnectionFailureIsReportedWithoutLeavingAPartialFile() throws Exception {
        String fileName = uniqueFileName();
        File output = outputFile(fileName);
        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();

        JdbcJarUtils.asyncDownload(
            "http://user:password@127.0.0.1:1/" + fileName + "?token=secret",
            error -> {
                failure.set(error);
                completion.countDown();
            });

        assertTrue(completion.await(10, TimeUnit.SECONDS));
        assertNotNull(failure.get());
        assertFalse(failure.get().getMessage().contains("user:password"));
        assertFalse(failure.get().getMessage().contains("token=secret"));
        assertFalse(output.exists());
    }

    @Test
    void asyncHttpFailureIsReportedWithoutLeavingAPartialFile() throws Exception {
        String fileName = uniqueFileName();
        File output = outputFile(fileName);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/" + fileName, exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();

        JdbcJarUtils.asyncDownload(
            "http://user:password@127.0.0.1:" + server.getAddress().getPort()
                + "/" + fileName + "?token=secret",
            error -> {
                failure.set(error);
                completion.countDown();
            });

        assertTrue(completion.await(10, TimeUnit.SECONDS));
        assertNotNull(failure.get());
        assertTrue(failure.get().getMessage().contains("HTTP 404"));
        assertFalse(failure.get().getMessage().contains("user:password"));
        assertFalse(failure.get().getMessage().contains("token=secret"));
        assertFalse(output.exists());
    }

    @Test
    void synchronousHttpFailureUsesSanitizedUrl() throws Exception {
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

        assertTrue(failure.getMessage().contains("HTTP 404"));
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

    private String uniqueFileName() {
        return "jdbc-driver-test-" + UUID.randomUUID() + ".jar";
    }

    private File outputFile(String fileName) {
        File output = new File(JdbcDriverConstants.DRIVER_LIB_PATH, fileName);
        filesToDelete.add(output);
        return output;
    }
}

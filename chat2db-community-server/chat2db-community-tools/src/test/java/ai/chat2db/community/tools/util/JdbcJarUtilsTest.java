package ai.chat2db.community.tools.util;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcJarUtilsTest {

    private final List<File> filesToDelete = new ArrayList<>();
    private HttpServer server;

    @TempDir
    private Path temporaryDirectory;

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

    @Test
    void configuredDriverUrlIsUsedWhenTheJarIsMissing() throws Exception {
        String fileName = uniqueFileName();
        File output = outputFile(fileName);
        byte[] driverBytes = "configured-driver".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maven/" + fileName, exchange -> {
            exchange.sendResponseHeaders(200, driverBytes.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(driverBytes);
            }
        });
        server.start();
        String configuredUrl = "http://127.0.0.1:" + server.getAddress().getPort()
            + "/maven/" + fileName;

        String path = JdbcJarUtils.getFullPath(fileName, List.of(configuredUrl));

        assertEquals(output.getAbsolutePath(), path);
        assertArrayEquals(driverBytes, java.nio.file.Files.readAllBytes(output.toPath()));
    }

    @Test
    void hsqldbJarResolvesToItsConfiguredMavenUrl() {
        String mavenUrl = "https://repo1.maven.org/maven2/org/hsqldb/hsqldb/2.7.3/hsqldb-2.7.3.jar";

        assertEquals(mavenUrl,
            JdbcJarUtils.getDownloadUrl("hsqldb-2.7.3.jar", List.of(mavenUrl)));
    }

    @Test
    void driverLibPathTracksUserHomeAfterClassInitialization() {
        JdbcDriverConstants.getDriverLibPath();
        String originalHome = System.getProperty("user.home");
        String originalRuntimeMode = System.getProperty("chat2db.runtime.mode");
        Path firstHome = temporaryDirectory.resolve("first-home");
        Path secondHome = temporaryDirectory.resolve("second-home");
        try {
            System.setProperty("chat2db.runtime.mode", "community");
            System.setProperty("user.home", firstHome.toString());
            assertEquals(expectedDriverPath(firstHome), JdbcDriverConstants.getDriverLibPath());

            System.setProperty("user.home", secondHome.toString());
            assertEquals(expectedDriverPath(secondHome), JdbcDriverConstants.getDriverLibPath());
        } finally {
            restoreProperty("user.home", originalHome);
            restoreProperty("chat2db.runtime.mode", originalRuntimeMode);
        }
    }

    private String uniqueFileName() {
        return "jdbc-driver-test-" + UUID.randomUUID() + ".jar";
    }

    private File outputFile(String fileName) {
        File output = new File(JdbcDriverConstants.getDriverLibPath(), fileName);
        filesToDelete.add(output);
        return output;
    }

    private String expectedDriverPath(Path home) {
        return home.resolve(".chat2db-community").resolve("jdbc-lib") + File.separator;
    }

    private void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}

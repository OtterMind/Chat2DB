package ai.chat2db.community.tools.util;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void asyncDownloadWorkerThreadsAreNamedDaemons() {
        Thread first = JdbcJarUtils.ASYNC_DOWNLOAD_THREAD_FACTORY.newThread(() -> { });
        Thread second = JdbcJarUtils.ASYNC_DOWNLOAD_THREAD_FACTORY.newThread(() -> { });

        assertTrue(first.isDaemon());
        assertTrue(second.isDaemon());
        assertTrue(first.getName().startsWith("chat2db-jdbc-download-"));
        assertTrue(second.getName().startsWith("chat2db-jdbc-download-"));
        assertNotEquals(first.getName(), second.getName());
    }

    @Test
    void asyncConnectionFailureIsReportedWithoutLeavingAPartialFile() throws Exception {
        String fileName = uniqueFileName();
        File output = outputFile(fileName);
        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        JdbcJarUtils.asyncDownload(
            "http://user:password@127.0.0.1:1/" + fileName + "?token=secret",
            error -> {
                callbackThread.set(Thread.currentThread());
                failure.set(error);
                completion.countDown();
            });

        assertTrue(completion.await(10, TimeUnit.SECONDS));
        assertNotNull(failure.get());
        assertFalse(failure.get().getMessage().contains("user:password"));
        assertFalse(failure.get().getMessage().contains("token=secret"));
        assertFalse(output.exists());
        assertNotNull(callbackThread.get());
        assertTrue(callbackThread.get().isDaemon());
        assertTrue(callbackThread.get().getName().startsWith("chat2db-jdbc-download-"));
    }

    @Test
    void asyncDownloadPublishesOnlyCompleteFiles() throws Exception {
        String fileName = uniqueFileName();
        File output = outputFile(fileName);
        byte[] content = "complete-jdbc-driver".getBytes(StandardCharsets.UTF_8);
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/" + fileName, exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            headersSent.countDown();
            try (OutputStream responseBody = exchange.getResponseBody()) {
                try {
                    if (!releaseBody.await(10, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                responseBody.write(content);
            }
        });
        server.start();
        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();

        JdbcJarUtils.asyncDownload(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/" + fileName,
            error -> {
                failure.set(error);
                completion.countDown();
            });

        File partialFile = null;
        try {
            assertTrue(headersSent.await(10, TimeUnit.SECONDS));
            partialFile = awaitPartialFile(output);
            assertNotNull(partialFile);
            filesToDelete.add(partialFile);
            assertFalse(output.exists());
        } finally {
            releaseBody.countDown();
        }
        assertTrue(completion.await(10, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertArrayEquals(content, Files.readAllBytes(output.toPath()));
        assertFalse(partialFile.exists());
    }

    @Test
    void asyncFailureDoesNotDeletePreviouslyPublishedDriver() throws Exception {
        String fileName = uniqueFileName();
        File output = outputFile(fileName);
        byte[] content = "complete-jdbc-driver".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/" + fileName, exchange -> {
            if ("fail=true".equals(exchange.getRequestURI().getRawQuery())) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(content);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + fileName;

        assertNull(awaitAsyncDownload(url));
        assertArrayEquals(content, Files.readAllBytes(output.toPath()));

        assertNotNull(awaitAsyncDownload(url + "?fail=true"));
        assertArrayEquals(content, Files.readAllBytes(output.toPath()));
    }

    @Test
    void interruptedResponseRemovesPartialFile() throws Exception {
        String fileName = uniqueFileName();
        File output = outputFile(fileName);
        byte[] partialContent = "partial".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/" + fileName, exchange -> {
            exchange.sendResponseHeaders(200, partialContent.length + 10);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(partialContent);
                responseBody.flush();
            }
        });
        server.start();

        IOException failure = awaitAsyncDownload(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/" + fileName);

        assertNotNull(failure);
        assertFalse(output.exists());
        assertNull(findPartialFile(output));
    }

    @Test
    void stalePartialFilesAreCleanedWithoutRemovingFreshDownloads() throws Exception {
        File directory = new File(JdbcDriverConstants.DRIVER_LIB_PATH);
        File stale = new File(directory, "jdbc-stale-" + UUID.randomUUID() + ".part");
        File fresh = new File(directory, "jdbc-fresh-" + UUID.randomUUID() + ".part");
        File matchingDirectory = new File(directory, "jdbc-directory-" + UUID.randomUUID() + ".part");
        filesToDelete.add(stale);
        filesToDelete.add(fresh);
        filesToDelete.add(matchingDirectory);
        Files.writeString(stale.toPath(), "stale", StandardCharsets.UTF_8);
        Files.writeString(fresh.toPath(), "fresh", StandardCharsets.UTF_8);
        assertTrue(matchingDirectory.mkdir());
        assertTrue(stale.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)));
        assertTrue(matchingDirectory.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)));

        JdbcJarUtils.cleanupStalePartialFiles(directory);

        assertFalse(stale.exists());
        assertTrue(fresh.exists());
        assertTrue(matchingDirectory.exists());
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

    private File awaitPartialFile(File output) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            File partialFile = findPartialFile(output);
            if (partialFile != null) {
                return partialFile;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private File findPartialFile(File output) {
        String prefix = "jdbc-" + output.getName() + "-";
        File[] partialFiles = output.getParentFile().listFiles(
            (directory, name) -> name.startsWith(prefix) && name.endsWith(".part"));
        return partialFiles == null || partialFiles.length == 0 ? null : partialFiles[0];
    }

    private IOException awaitAsyncDownload(String url) throws Exception {
        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();
        JdbcJarUtils.asyncDownload(url, error -> {
            failure.set(error);
            completion.countDown();
        });
        assertTrue(completion.await(10, TimeUnit.SECONDS));
        return failure.get();
    }
}

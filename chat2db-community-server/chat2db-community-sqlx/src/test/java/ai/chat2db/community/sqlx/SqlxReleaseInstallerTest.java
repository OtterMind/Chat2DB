package ai.chat2db.community.sqlx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ai.chat2db.community.tools.sqlx.SqlxException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlxReleaseInstallerTest {

    @Test
    void readsTheDigestForTheRequestedAsset() {
        String sums = "aaa  sqlx-linux-x64.zip\n" + "bbb *sqlx-macos-arm64.zip\n";
        assertEquals("bbb", SqlxReleaseInstaller.checksumFor(sums, "sqlx-macos-arm64.zip"));
        assertEquals("aaa", SqlxReleaseInstaller.checksumFor(sums, "sqlx-linux-x64.zip"));
        assertNull(SqlxReleaseInstaller.checksumFor(sums, "sqlx-windows-x64.zip"));
        assertNull(SqlxReleaseInstaller.checksumFor("", "sqlx-linux-x64.zip"));
    }

    @Test
    void extractsOnlyTheExecutable(@TempDir Path directory) throws IOException {
        Path archive = directory.resolve("sqlx-linux-x64.zip");
        writeArchive(archive, "LICENSE", "licence", "sqlx", "#!/bin/sh\n");
        Path target = directory.resolve("sqlx");
        SqlxReleaseInstaller.extractEntry(archive, "sqlx", target);
        assertEquals("#!/bin/sh\n", Files.readString(target));
        SqlxException failure = assertThrows(SqlxException.class,
                () -> SqlxReleaseInstaller.extractEntry(archive, "missing", directory.resolve("other")));
        assertEquals("sqlx.invalid_archive", failure.getCode());
    }

    @Test
    void hashesTheDownloadedArchive(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("payload.bin");
        Files.writeString(file, "sqlx");
        assertEquals(sha256Hex("sqlx".getBytes(StandardCharsets.UTF_8)), SqlxReleaseInstaller.sha256(file));
    }

    @Test
    void installsAVerifiedReleaseFromTheConfiguredChannel(@TempDir Path directory) throws IOException {
        assumePosix();
        byte[] archive = archiveBytes(stubExecutable("0.1.16"));
        String digest = sha256Hex(archive);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest/download/release-version.txt", exchange -> respond(exchange, "0.1.16"));
        server.createContext("/download/v0.1.16/sqlx-linux-x64.zip", exchange -> respond(exchange, archive));
        server.createContext("/download/v0.1.16/SHA256SUMS",
                exchange -> respond(exchange, digest + "  sqlx-linux-x64.zip\n"));
        server.start();
        try {
            SqlxPlatform platform = SqlxPlatform.of("Linux", "x86_64").orElseThrow();
            Path installDirectory = directory.resolve("bin");
            SqlxReleaseInstaller installer = new SqlxReleaseInstaller(
                    "http://127.0.0.1:" + server.getAddress().getPort(), installDirectory);
            List<String> steps = new ArrayList<>();
            SqlxReleaseInstaller.Installed installed = installer.install(null, platform,
                    new SqlxReleaseInstaller.Progress() {
                        @Override
                        public void step(String step) {
                            steps.add(step);
                        }

                        @Override
                        public void downloading(long downloaded, long total) {
                            // Progress reporting is covered by the status service tests.
                        }
                    }, () -> false);
            assertEquals("0.1.16", installed.version());
            assertEquals(digest, installed.sha256());
            assertNull(installed.note(), "an install into the official directory needs no explanation");
            assertEquals(installDirectory.resolve("sqlx"), installed.executable());
            assertTrue(Files.isExecutable(installed.executable()));
            assertTrue(SqlxExecutable.isOtterMind(installed.executable()));
            assertTrue(steps.contains("verifying"), steps.toString());
            assertTrue(steps.contains("extracting"), steps.toString());
            assertTrue(steps.contains("validating"), steps.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesAnArchiveThatDoesNotMatchThePublishedChecksum(@TempDir Path directory) throws IOException {
        byte[] archive = archiveBytes(stubExecutable("0.1.16"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/download/v0.1.16/sqlx-linux-x64.zip", exchange -> respond(exchange, archive));
        server.createContext("/download/v0.1.16/SHA256SUMS",
                exchange -> respond(exchange, "0".repeat(64) + "  sqlx-linux-x64.zip\n"));
        server.start();
        try {
            SqlxPlatform platform = SqlxPlatform.of("Linux", "x86_64").orElseThrow();
            SqlxReleaseInstaller installer = new SqlxReleaseInstaller(
                    "http://127.0.0.1:" + server.getAddress().getPort(), directory.resolve("bin"));
            SqlxException failure = assertThrows(SqlxException.class,
                    () -> installer.install("0.1.16", platform, null, () -> false));
            assertEquals("sqlx.checksum_mismatch", failure.getCode());
            assertTrue(Files.notExists(directory.resolve("bin").resolve("sqlx")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToTheManagedDirectoryWhenTheOfficialOneIsTaken(@TempDir Path directory) throws IOException {
        assumePosix();
        Path installDirectory = Files.createDirectories(directory.resolve("bin"));
        Path existing = installDirectory.resolve("sqlx");
        Files.writeString(existing, "#!/bin/sh\necho \"another tool\"\n");
        SqlxReleaseInstaller.makeExecutable(existing);
        byte[] archive = archiveBytes(stubExecutable("0.1.16"));
        String digest = sha256Hex(archive);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/download/v0.1.16/sqlx-linux-x64.zip", exchange -> respond(exchange, archive));
        server.createContext("/download/v0.1.16/SHA256SUMS",
                exchange -> respond(exchange, digest + "  sqlx-linux-x64.zip\n"));
        server.start();
        try {
            SqlxPlatform platform = SqlxPlatform.of("Linux", "x86_64").orElseThrow();
            Path managed = directory.resolve("managed");
            SqlxReleaseInstaller installer = new SqlxReleaseInstaller(
                    "http://127.0.0.1:" + server.getAddress().getPort(), installDirectory, managed);
            SqlxReleaseInstaller.Installed installed = installer.install("0.1.16", platform, null, () -> false);
            // The foreign program keeps its place, and SQLX still becomes available from the managed directory.
            assertEquals("#!/bin/sh\necho \"another tool\"\n", Files.readString(existing));
            assertEquals(managed.resolve("sqlx"), installed.executable());
            assertTrue(SqlxExecutable.isOtterMind(installed.executable()));
            assertNotNull(installed.note());
            assertTrue(installed.note().contains(installDirectory.toString()), installed.note());
        } finally {
            server.stop(0);
        }
    }

    private static void assumePosix() {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "the stub executable is a shell script");
    }

    private static String stubExecutable(String version) {
        return "#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then echo \"" + version + " (OtterMind/sqlx)\"; exit 0; fi\nexit 0\n";
    }

    private static byte[] archiveBytes(String executable) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("LICENSE"));
            zip.write("licence".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("sqlx"));
            zip.write(executable.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void writeArchive(Path archive, String firstName, String firstContent, String secondName,
            String secondContent) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(firstName));
            zip.write(firstContent.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(secondName));
            zip.write(secondContent.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

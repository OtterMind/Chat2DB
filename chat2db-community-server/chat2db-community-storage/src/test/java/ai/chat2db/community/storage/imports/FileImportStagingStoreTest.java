package ai.chat2db.community.storage.imports;

import ai.chat2db.community.domain.api.model.file.StagedImportFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileImportStagingStoreTest {

    /** A pid no process can hold on the platforms this product ships on. */
    private static final long DEAD_PID = 999999L;

    @TempDir
    Path tempDirectory;

    private ObjectMapper objectMapper;

    private Path rootDirectory;

    private Path legacyDirectory;

    private FileImportStagingStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        rootDirectory = tempDirectory.resolve("storage").resolve(FileImportStagingStore.STAGING_DIRECTORY_NAME);
        legacyDirectory = tempDirectory.resolve("legacy").resolve(FileImportStagingStore.LEGACY_DIRECTORY_NAME);
        store = new FileImportStagingStore(objectMapper, rootDirectory, legacyDirectory);
    }

    @Test
    void createsTheStagingDirectoryAndWritesItsOwnEmptyIndexOnStartup() throws IOException {
        store.prepareRootDirectory();

        assertAll(
                () -> assertTrue(Files.isDirectory(rootDirectory), "the staging directory must exist"),
                () -> assertTrue(Files.exists(ownIndexFile()), "the process must announce its own index"),
                () -> assertEquals(ProcessHandle.current().pid(), readOwnIndex().path("pid").asLong()),
                () -> assertEquals(List.of(), store.list()));
    }

    @Test
    void storesAnUploadInsideTheStagingDirectoryWithoutLeavingATemporaryFile() throws IOException {
        Path source = Files.writeString(tempDirectory.resolve("rows.csv"), "id,name\n1,Ada\n");

        String fileId = store.store(source, "csv");

        Path stored = rootDirectory.resolve(fileId + ".csv");
        assertAll(
                () -> assertTrue(Files.isRegularFile(stored), "the upload must be stored under its identifier"),
                () -> assertEquals("id,name\n1,Ada\n", Files.readString(stored)),
                () -> assertEquals(stored, store.find(fileId).orElseThrow().path()),
                () -> assertEquals("csv", store.find(fileId).orElseThrow().extension()),
                () -> assertEquals(List.of(), temporaryFiles(), "no half-written upload may stay behind"));
    }

    @Test
    void rejectsAnUnsupportedExtensionAndAFileNameThatLeavesTheDirectory() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> store.store(tempDirectory.resolve("rows.csv"), "zip")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> store.resolveStagedFile("../outside", "csv")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> store.resolveStagedFile(UUID.randomUUID().toString(), "zip")),
                () -> assertTrue(store.find("../outside").isEmpty()),
                () -> assertTrue(store.find("not-an-identifier").isEmpty()));
    }

    @Test
    void listsOnlyStoredUploadsOldestFirstAndNeverAProcessIndexOrATemporaryFile() throws IOException {
        Files.createDirectories(rootDirectory);
        String newest = storeUpload("new.csv", Instant.parse("2026-10-10T11:00:00Z"));
        String oldest = storeUpload("old.csv", Instant.parse("2026-10-09T19:00:00Z"));
        writeIndex(DEAD_PID, Map.of());
        Files.writeString(rootDirectory.resolve(".import-staging-" + UUID.randomUUID() + ".tmp"), "half written");
        Files.writeString(rootDirectory.resolve("notes.txt"), "not an upload");

        List<StagedImportFile> stored = store.list();

        assertAll(
                () -> assertEquals(List.of(oldest + ".csv", newest + ".csv"),
                        stored.stream().map(file -> file.path().getFileName().toString()).collect(Collectors.toList())),
                () -> assertEquals(2, stored.size(), "an index, a temporary file and a foreign name are not uploads"),
                () -> assertTrue(store.find(DEAD_PID + "").isEmpty()));
    }

    @Test
    void ignoresAnEntryThatPointsOutOfTheStagingDirectory() throws IOException {
        Path outside = Files.writeString(tempDirectory.resolve("outside.csv"), "id\n1\n");
        Files.createDirectories(rootDirectory);
        Path link = rootDirectory.resolve(UUID.randomUUID() + ".csv");
        Files.createSymbolicLink(link, outside);

        assertAll(
                () -> assertTrue(store.find(link.getFileName().toString().replace(".csv", "")).isEmpty()),
                () -> assertEquals(List.of(), store.list()));
    }

    @Test
    void recordsAClaimInTheProcessIndexAndForgetsItWhenTheUploadIsReleased() throws IOException {
        String fileId = storeUpload("rows.csv", Instant.now());
        Instant claimedAt = Instant.ofEpochMilli(1791600123456L);

        assertTrue(store.saveClaim(fileId, claimedAt));

        JsonNode index = readOwnIndex();
        assertAll(
                () -> assertEquals(claimedAt.toEpochMilli(), index.path("claims").path(fileId).asLong()),
                () -> assertEquals(claimedAt, store.claims().get(fileId)),
                () -> assertEquals(claimedAt, store.list().get(0).claimedAt()),
                () -> assertTrue(store.list().get(0).isClaimed()),
                () -> assertEquals(List.of(), temporaryFiles(), "the index is replaced in one step"));

        Optional<StagedImportFile> deleted = store.delete(fileId);

        assertAll(
                () -> assertTrue(deleted.isPresent()),
                () -> assertFalse(Files.exists(rootDirectory.resolve(fileId + ".csv"))),
                () -> assertTrue(store.claims().isEmpty(), "a released upload must not stay claimed"),
                () -> assertEquals(List.of(), store.list()));
    }

    @Test
    void releasesTheClaimsOfAProcessThatIsGoneAndRemovesItsIndex() throws IOException {
        String fileId = storeUpload("rows.csv", Instant.now());
        writeIndex(DEAD_PID, Map.of(fileId, 1791600123456L));

        store.prepareRootDirectory();

        assertAll(
                () -> assertFalse(Files.exists(indexFile(DEAD_PID)), "the index of a gone process is removed"),
                () -> assertTrue(store.claims().isEmpty(), "its claims are released"),
                () -> assertNull(store.list().get(0).claimedAt(), "the upload itself stays, unclaimed"),
                () -> assertTrue(Files.isRegularFile(rootDirectory.resolve(fileId + ".csv"))));
    }

    @Test
    void keepsTheClaimsOfAProcessThatIsStillRunning() throws IOException {
        String fileId = store.store(Files.writeString(tempDirectory.resolve("rows.csv"), "id\n1\n"), "csv");
        long peerPid = runningForeignPid();
        writeIndex(peerPid, Map.of(fileId, 1791600123456L));

        store.prepareRootDirectory();

        assertAll(
                () -> assertTrue(Files.exists(indexFile(peerPid)), "a running instance keeps its index"),
                () -> assertEquals(Instant.ofEpochMilli(1791600123456L), store.claims().get(fileId)),
                () -> assertEquals(Instant.ofEpochMilli(1791600123456L), store.list().get(0).claimedAt()));
    }

    /** The process that started this test: it is running and it is not this JVM. */
    private long runningForeignPid() {
        return ProcessHandle.current().parent()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .orElseThrow(() -> new IllegalStateException("no live parent process"));
    }

    @Test
    void treatsADamagedIndexAsTheIndexOfAProcessThatIsGone() throws IOException {
        String fileId = storeUpload("rows.csv", Instant.now());
        Files.writeString(indexFile(DEAD_PID), "{ not json at all");

        store.prepareRootDirectory();

        assertAll(
                () -> assertFalse(Files.exists(indexFile(DEAD_PID)), "a damaged index is removed"),
                () -> assertTrue(store.claims().isEmpty()),
                () -> assertNull(store.list().get(0).claimedAt()),
                () -> assertTrue(Files.isRegularFile(rootDirectory.resolve(fileId + ".csv"))));
    }

    @Test
    void overwritesTheIndexOfAnEarlierRunWithTheSamePid() throws IOException {
        String fileId = storeUpload("rows.csv", Instant.now());
        Files.createDirectories(rootDirectory);
        Files.writeString(ownIndexFile(), objectMapper.writeValueAsString(
                new FileImportStagingStore.ProcessIndex(ProcessHandle.current().pid(), 1L, Map.of(fileId, 1L))));

        store.prepareRootDirectory();

        assertAll(
                () -> assertTrue(readOwnIndex().path("claims").isEmpty()),
                () -> assertTrue(store.claims().isEmpty()));
    }

    @Test
    void movesTheUploadsOfTheLegacyLocationAndKeepsThatDirectory() throws IOException {
        String fileId = UUID.randomUUID().toString();
        Files.createDirectories(legacyDirectory);
        Path legacyUpload = Files.writeString(legacyDirectory.resolve(fileId + ".csv"), "id\n1\n");
        Path untouched = Files.writeString(legacyDirectory.resolve("notes.txt"), "not an upload");

        int moved = store.migrateLegacyFiles();

        assertAll(
                () -> assertEquals(1, moved),
                () -> assertFalse(Files.exists(legacyUpload), "the legacy upload moved"),
                () -> assertEquals("id\n1\n", Files.readString(rootDirectory.resolve(fileId + ".csv"))),
                () -> assertTrue(Files.isDirectory(legacyDirectory), "the legacy directory itself stays"),
                () -> assertTrue(Files.exists(untouched), "a foreign file is never touched"),
                () -> assertEquals(0, store.migrateLegacyFiles(), "the migration is idempotent"));
    }

    @Test
    void keepsALegacyUploadWhoseStagingFileAlreadyExists() throws IOException {
        String fileId = UUID.randomUUID().toString();
        Files.createDirectories(rootDirectory);
        Files.createDirectories(legacyDirectory);
        Files.writeString(rootDirectory.resolve(fileId + ".csv"), "stored");
        Path legacyUpload = Files.writeString(legacyDirectory.resolve(fileId + ".csv"), "legacy");

        int moved = store.migrateLegacyFiles();

        assertAll(
                () -> assertEquals(0, moved),
                () -> assertEquals("stored", Files.readString(rootDirectory.resolve(fileId + ".csv"))),
                () -> assertEquals("legacy", Files.readString(legacyUpload)));
    }

    @Test
    void sweepsOnlyTheTemporaryUploadsOfACrashedRun() throws IOException {
        Files.createDirectories(rootDirectory);
        Path temporary = Files.writeString(rootDirectory.resolve(
                FileImportStagingStore.TEMPORARY_FILE_PREFIX + UUID.randomUUID() + FileImportStagingStore.TEMPORARY_FILE_SUFFIX),
                "half written");

        store.prepareRootDirectory();

        assertFalse(Files.exists(temporary));
    }

    private String storeUpload(String name, Instant lastModified) throws IOException {
        String fileId = store.store(Files.writeString(tempDirectory.resolve(name + UUID.randomUUID()), "id\n1\n"), "csv");
        Files.setLastModifiedTime(rootDirectory.resolve(fileId + ".csv"), FileTime.from(lastModified));
        return fileId;
    }

    private void writeIndex(long pid, Map<String, Long> claims) throws IOException {
        Files.createDirectories(rootDirectory);
        Files.writeString(indexFile(pid),
                objectMapper.writeValueAsString(new FileImportStagingStore.ProcessIndex(pid, 1L, claims)));
    }

    private Path indexFile(long pid) {
        return rootDirectory.resolve(
                FileImportStagingStore.INDEX_FILE_PREFIX + pid + FileImportStagingStore.INDEX_FILE_SUFFIX);
    }

    private Path ownIndexFile() {
        return indexFile(ProcessHandle.current().pid());
    }

    private JsonNode readOwnIndex() throws IOException {
        return objectMapper.readTree(Files.readString(ownIndexFile(), StandardCharsets.UTF_8));
    }

    private List<Path> temporaryFiles() throws IOException {
        if (!Files.isDirectory(rootDirectory)) {
            return List.of();
        }
        try (var entries = Files.list(rootDirectory)) {
            return entries.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(".") && name.endsWith(FileImportStagingStore.TEMPORARY_FILE_SUFFIX);
            }).collect(Collectors.toList());
        }
    }
}

package ai.chat2db.community.domain.core.impl.file;

import ai.chat2db.community.domain.api.model.file.StagedImportFile;
import ai.chat2db.community.storage.imports.FileImportStagingStore;
import ai.chat2db.community.tools.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the shipped policy of the staging service against the real file store: where an upload
 * lands, how long it may stay, when a claim protects it, and what happens when the staging
 * directory is over its budget.
 */
class ImportFileStagingServiceTest {

    private static final Duration MAX_AGE = Duration.ofHours(24);

    private static final Duration CLAIMED_MAX_AGE = Duration.ofDays(7);

    /** A pid no process can hold on the platforms this product ships on. */
    private static final long DEAD_PID = 999999L;

    @TempDir
    Path tempDirectory;

    private ObjectMapper objectMapper;

    /** Where the uploads live now: {@code <state directory>/<env>/storage/import-staging}. */
    private Path storageDirectory;

    /** Where the uploads lived before: {@code <state directory>/import-preview}. */
    private Path legacyDirectory;

    private FileImportStagingStore store;

    private ImportFileStagingService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        storageDirectory = tempDirectory.resolve("dev").resolve(FileImportStagingStore.STORAGE_DIRECTORY_NAME)
                .resolve(FileImportStagingStore.STAGING_DIRECTORY_NAME);
        legacyDirectory = tempDirectory.resolve(FileImportStagingStore.LEGACY_DIRECTORY_NAME);
        store = new FileImportStagingStore(objectMapper, storageDirectory, legacyDirectory);
        store.prepareRootDirectory();
        service = service(ImportFileStagingService.DEFAULT_MAX_TOTAL_BYTES, MAX_AGE, CLAIMED_MAX_AGE);
    }

    @Test
    void shipsTheUnchangedLifetimesAndLimits() {
        assertAll(
                () -> assertEquals(Duration.ofHours(24), ImportFileStagingService.MAX_AGE),
                () -> assertEquals(Duration.ofDays(7), ImportFileStagingService.CLAIMED_MAX_AGE),
                () -> assertEquals(50L * 1024 * 1024, ImportFileStagingService.MAX_SIZE_BYTES),
                () -> assertEquals(2L * 1024 * 1024 * 1024, ImportFileStagingService.DEFAULT_MAX_TOTAL_BYTES));
    }

    @Test
    void stagesIntoTheWorkspaceStorageDirectoryAndReleasesTheUpload() throws Exception {
        File source = Files.writeString(tempDirectory.resolve("source.csv"), "id,name\n1,Ada\n").toFile();

        String fileId = service.stage(source, "input.csv");
        File resolved = service.resolve(fileId);

        assertAll(
                () -> assertTrue(resolved.isFile()),
                () -> assertEquals(fileId + ".csv", resolved.getName()),
                () -> assertEquals(storageDirectory, resolved.toPath().getParent(),
                        "an upload must land in <env>/storage/import-staging"),
                () -> assertEquals("id,name\n1,Ada\n", Files.readString(resolved.toPath())),
                () -> assertTrue(legacyUploads().isEmpty(), "the legacy location must stay out of the write path"));

        service.claimForTask(fileId);
        service.release(fileId);

        assertAll(
                () -> assertFalse(Files.exists(resolved.toPath())),
                () -> assertTrue(store.claims().isEmpty(), "a released upload must not stay claimed"));
    }

    @Test
    void rejectsInvalidFileId() {
        assertAll(
                () -> assertThrows(BusinessException.class, () -> service.resolve("../outside")),
                () -> assertThrows(BusinessException.class, () -> service.resolve(null)),
                () -> assertThrows(BusinessException.class,
                        () -> service.resolve(UUID.randomUUID().toString().replace("-", ""))));
    }

    @Test
    void releaseOfAnUnknownUploadChangesNothing() {
        service.release(UUID.randomUUID().toString());
        service.release("../outside");
    }

    @Test
    void acceptsEverySupportedWebImportFormat() throws Exception {
        for (String extension : new String[] {"csv", "xls", "xlsx", "json", "sql"}) {
            File source = Files.writeString(tempDirectory.resolve("source." + extension), "test").toFile();
            String fileId = service.stage(source, "input." + extension);
            File resolved = service.resolve(fileId);

            assertEquals(fileId + "." + extension, resolved.getName());
            service.release(fileId);
        }
    }

    @Test
    void rejectsUnsupportedImportFormat() throws Exception {
        File source = Files.writeString(tempDirectory.resolve("source.zip"), "test").toFile();

        assertThrows(BusinessException.class, () -> service.stage(source, "input.zip"));
    }

    @Test
    void dropsAnUnclaimedUploadOnlyWhenItsLifetimeIsOver() throws Exception {
        ImportFileStagingService shortLived = service(ImportFileStagingService.DEFAULT_MAX_TOTAL_BYTES,
                Duration.ofHours(1), CLAIMED_MAX_AGE);
        String fresh = shortLived.stage(source("fresh.csv"), "fresh.csv");
        String justInside = shortLived.stage(source("inside.csv"), "inside.csv");
        String justOutside = shortLived.stage(source("outside.csv"), "outside.csv");
        age(justInside, Duration.ofHours(1).minusMinutes(1));
        age(justOutside, Duration.ofHours(1).plusMinutes(1));

        shortLived.cleanupStagingFiles();

        assertAll(
                () -> assertTrue(Files.exists(storedFile(fresh)), "a fresh upload must survive the sweep"),
                () -> assertTrue(Files.exists(storedFile(justInside)), "one minute inside the lifetime is still alive"),
                () -> assertFalse(Files.exists(storedFile(justOutside)), "one minute outside the lifetime is gone"),
                () -> assertEquals(Set.of(fresh, justInside), Set.copyOf(stagedFileIds())));
    }

    @Test
    void protectsAClaimedUploadUntilTheClaimLifetimeIsOver() throws Exception {
        ImportFileStagingService shortLived = service(ImportFileStagingService.DEFAULT_MAX_TOTAL_BYTES,
                Duration.ofHours(1), Duration.ofHours(3));
        String claimed = shortLived.stage(source("claimed.csv"), "claimed.csv");
        shortLived.claimForTask(claimed);
        age(claimed, Duration.ofHours(2));

        shortLived.cleanupStagingFiles();

        assertTrue(Files.exists(storedFile(claimed)), "a claim protects an upload past the unclaimed lifetime");

        store.saveClaim(claimed, Instant.now().minus(Duration.ofHours(4)));
        shortLived.cleanupStagingFiles();

        assertFalse(Files.exists(storedFile(claimed)), "a claim older than the claim lifetime stops protecting");
    }

    @Test
    void keepsAClaimThatOutlivedTheServiceInstanceThatRecordedIt() throws Exception {
        String claimed = service.stage(source("claimed.csv"), "claimed.csv");
        service.claimForTask(claimed);
        age(claimed, Duration.ofHours(25));

        // A new instance over the same directory has no heap state at all: the claim has to come from
        // the process index, otherwise the upload would be deleted by the instance below.
        FileImportStagingStore restarted = new FileImportStagingStore(objectMapper, storageDirectory,
                legacyDirectory);
        ImportFileStagingService restartedService = new ImportFileStagingService(restarted,
                ImportFileStagingService.DEFAULT_MAX_TOTAL_BYTES, MAX_AGE, CLAIMED_MAX_AGE);

        restartedService.cleanupStagingFiles();

        assertAll(
                () -> assertTrue(Files.exists(storedFile(claimed)), "the persisted claim still protects the upload"),
                () -> assertEquals(Instant.now().getEpochSecond(),
                        restarted.list().get(0).claimedAt().getEpochSecond(), 60L));
    }

    @Test
    void keepsAnUploadClaimedByAnotherRunningInstance() throws Exception {
        // Two instances share one state directory, so the claim of the other one has to be read from
        // its index and its upload must stay untouched.
        String claimed = service.stage(source("peer.csv"), "peer.csv");
        age(claimed, Duration.ofHours(25));
        long peerPid = runningForeignPid();
        writeIndex(peerPid, Map.of(claimed, Instant.now().toEpochMilli()));

        service.cleanupStagingFiles();

        assertAll(
                () -> assertTrue(Files.exists(storedFile(claimed)), "a running instance keeps its claims"),
                () -> assertTrue(Files.exists(indexFile(peerPid)), "its index is not touched"),
                () -> assertEquals(Instant.now().getEpochSecond(),
                        store.list().get(0).claimedAt().getEpochSecond(), 60L));
    }

    /** The process that started this test: it is running and it is not this JVM. */
    private long runningForeignPid() {
        return ProcessHandle.current().parent()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .orElseThrow(() -> new IllegalStateException("no live parent process"));
    }

    @Test
    void releasesTheClaimOfAnInstanceThatIsGone() throws Exception {
        String claimed = service.stage(source("orphan.csv"), "orphan.csv");
        age(claimed, Duration.ofHours(25));
        writeIndex(DEAD_PID, Map.of(claimed, Instant.now().toEpochMilli()));

        // The startup takeover drops the claim of the process that is gone, so the unchanged
        // unclaimed lifetime decides about the upload again.
        store.prepareRootDirectory();
        service.cleanupStagingFiles();

        assertAll(
                () -> assertFalse(Files.exists(indexFile(DEAD_PID)), "the index of a gone instance is removed"),
                () -> assertFalse(Files.exists(storedFile(claimed)),
                        "an upload of a gone instance is measured by the unclaimed lifetime again"));
    }

    @Test
    void dropsTheOldestUploadsFirstWhenTheDirectoryIsOverItsBudget() throws Exception {
        String oldest = stageAged("oldest.csv", Duration.ofMinutes(30));
        String middle = stageAged("middle.csv", Duration.ofMinutes(20));
        String newest = stageAged("newest.csv", Duration.ofMinutes(10));
        long budget = storedSize(newest);
        ImportFileStagingService budgeted = service(budget, MAX_AGE, CLAIMED_MAX_AGE);

        budgeted.cleanupStagingFiles();

        assertAll(
                () -> assertFalse(Files.exists(storedFile(oldest)), "the oldest upload goes first"),
                () -> assertFalse(Files.exists(storedFile(middle)), "the next oldest follows until the budget fits"),
                () -> assertTrue(Files.exists(storedFile(newest)), "the budget is measured, not emptied"),
                () -> assertEquals(List.of(newest), stagedFileIds()));
    }

    @Test
    void neverDropsAClaimedUploadOverTheBudget() throws Exception {
        String claimedOldest = stageAged("claimed.csv", Duration.ofMinutes(30));
        service.claimForTask(claimedOldest);
        String unclaimed = stageAged("unclaimed.csv", Duration.ofMinutes(20));
        ImportFileStagingService budgeted = service(storedSize(claimedOldest), MAX_AGE, CLAIMED_MAX_AGE);

        budgeted.cleanupStagingFiles();

        assertAll(
                () -> assertTrue(Files.exists(storedFile(claimedOldest)), "a claim in force is never evicted"),
                () -> assertFalse(Files.exists(storedFile(unclaimed)), "the eviction continues with the next upload"),
                () -> assertEquals(List.of(claimedOldest), stagedFileIds()));
    }

    @Test
    void adoptsTheLegacyUploadsOnStartupAndAppliesTheSameLifetimeToThem() throws Exception {
        String fresh = legacyUpload("fresh.csv", Duration.ofMinutes(5));
        String stale = legacyUpload("stale.csv", Duration.ofHours(25));

        service.prepareStagingOnStartup();

        assertAll(
                () -> assertEquals(List.of(fresh), stagedFileIds(), "the fresh legacy upload is adopted"),
                () -> assertFalse(Files.exists(storedFile(stale)), "an expired legacy upload is dropped right away"),
                () -> assertTrue(Files.exists(legacyDirectory), "the legacy directory itself stays"),
                () -> assertTrue(legacyUploads().isEmpty(), "the legacy directory is left without uploads"));
    }

    @Test
    void cleanupStagingFilesIsSafeWithoutAnyUpload() throws Exception {
        Files.deleteIfExists(indexFile(ProcessHandle.current().pid()));
        Files.deleteIfExists(storageDirectory);

        service.cleanupStagingFiles();

        assertTrue(stagedFileIds().isEmpty());
    }

    private ImportFileStagingService service(long maxTotalBytes, Duration maxAge, Duration claimedMaxAge) {
        return new ImportFileStagingService(store, maxTotalBytes, maxAge, claimedMaxAge);
    }

    private File source(String name) throws IOException {
        return Files.writeString(tempDirectory.resolve(name + "-" + UUID.randomUUID()), "id,name\n1,Ada\n").toFile();
    }

    private String stageAged(String name, Duration age) throws Exception {
        String fileId = service.stage(source(name), name);
        age(fileId, age);
        return fileId;
    }

    /** Writes one upload straight into the legacy location, as the previous release did. */
    private String legacyUpload(String name, Duration age) throws IOException {
        Files.createDirectories(legacyDirectory);
        String fileId = UUID.randomUUID().toString();
        Path file = Files.writeString(legacyDirectory.resolve(fileId + ".csv"), "id,name\n1,Ada\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)));
        return fileId;
    }

    private void age(String fileId, Duration age) throws IOException {
        Files.setLastModifiedTime(storedFile(fileId), FileTime.from(Instant.now().minus(age)));
    }

    private Path storedFile(String fileId) {
        return storageDirectory.resolve(fileId + ".csv");
    }

    private long storedSize(String fileId) throws IOException {
        return Files.size(storedFile(fileId));
    }

    private List<String> stagedFileIds() {
        return store.list().stream().map(StagedImportFile::fileId).collect(Collectors.toList());
    }

    private List<Path> legacyUploads() throws IOException {
        if (!Files.isDirectory(legacyDirectory)) {
            return List.of();
        }
        try (var entries = Files.list(legacyDirectory)) {
            return entries.collect(Collectors.toList());
        }
    }

    /** Writes the process index of another instance exactly as the store writes it. */
    private void writeIndex(long pid, Map<String, Long> claims) throws IOException {
        Files.writeString(indexFile(pid),
                objectMapper.writeValueAsString(Map.of("pid", pid, "startedAt", 1L, "claims", claims)));
    }

    private Path indexFile(long pid) {
        return storageDirectory.resolve("index-" + pid + ".json");
    }
}

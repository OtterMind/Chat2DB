package ai.chat2db.community.storage.imports;

import ai.chat2db.community.domain.api.model.file.StagedImportFile;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingStore;
import ai.chat2db.community.domain.api.service.file.ImportStagingFiles;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File backed {@link IImportFileStagingStore}.
 * <p>
 * Layout: {@code <storage base path>/import-staging/<identifier>.<extension>} for the uploads,
 * {@code index-<pid>.json} for the claims of one running process, and a hidden temporary file while
 * an upload or an index is being written. The identifiers are random UUIDs and every process owns
 * its own index file, so two instances sharing one state directory never pick the same file name and
 * never mistake each other's live uploads for leftovers.
 * <p>
 * The claims live in the process index rather than in memory, so they survive a restart of the
 * bookkeeping even though the process does not: the next process reads the index of a process that
 * is gone, treats its claims as released, and removes the stale index. A claim of a process that is
 * still running is left alone.
 * <p>
 * The uploads of the location that predates the workspace storage directory - {@code
 * <state directory>/import-preview} with the same file names - are moved here on request of the
 * caller. Nothing is deleted by the migration: an upload that cannot be moved stays where it was, so
 * a failing migration never loses an upload.
 */
@Slf4j
@Component
public class FileImportStagingStore implements IImportFileStagingStore {

    /** Directory name pieces of the workspace storage location. */
    public static final String STORAGE_DIRECTORY_NAME = "storage";

    public static final String STAGING_DIRECTORY_NAME = "import-staging";

    /** The directory uploads were written to before the workspace storage directory carried them. */
    public static final String LEGACY_DIRECTORY_NAME = "import-preview";

    /**
     * Every process writes its own index, so a claim of a process that is still running is
     * recognisable and a claim of a process that is gone can be released.
     */
    static final String INDEX_FILE_PREFIX = "index-";

    static final String INDEX_FILE_SUFFIX = ".json";

    static final String TEMPORARY_FILE_PREFIX = ".import-staging-";

    static final String TEMPORARY_FILE_SUFFIX = ".tmp";

    /** The process whose index this store writes. */
    private final long ownPid;

    private final ObjectMapper objectMapper;

    private final Path rootDirectory;

    private final Path legacyDirectory;

    /** When this process started writing its index; recorded so a stale index is recognisable. */
    private final long startedAt = System.currentTimeMillis();

    /** Serialises the read-modify-write of the own index; two claims must not lose one another. */
    private final ReentrantLock indexLock = new ReentrantLock();

    @Autowired
    public FileImportStagingStore(ObjectMapper objectMapper) {
        this(objectMapper, defaultRootDirectory(), defaultLegacyDirectory());
    }

    /** Test seam: the shipped locations are derived from the user home, tests pass their own. */
    public FileImportStagingStore(ObjectMapper objectMapper, Path rootDirectory, Path legacyDirectory) {
        this.objectMapper = objectMapper;
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        this.legacyDirectory = legacyDirectory.toAbsolutePath().normalize();
        this.ownPid = ProcessHandle.current().pid();
    }

    /** The workspace storage location of the staged uploads. */
    public static Path defaultRootDirectory() {
        return Path.of(ConfigUtils.getEnvBasePath(), STORAGE_DIRECTORY_NAME, STAGING_DIRECTORY_NAME);
    }

    /** The location uploads were written to before the workspace storage directory carried them. */
    public static Path defaultLegacyDirectory() {
        return Path.of(ConfigUtils.getBasePath(), LEGACY_DIRECTORY_NAME);
    }

    @PostConstruct
    public void prepareRootDirectory() {
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create import staging directory: " + rootDirectory, e);
        }
        deleteTemporaryFiles();
        cleanupPreviousRun();
    }

    @Override
    public String store(Path source, String extension) throws IOException {
        if (!ImportStagingFiles.isAllowedExtension(extension)) {
            throw new IllegalArgumentException("Unsupported import upload extension: " + extension);
        }
        Files.createDirectories(rootDirectory);
        String fileId = UUID.randomUUID().toString();
        Path target = resolveStagedFile(fileId, extension);
        // The upload becomes visible under its own name in one step, so a crash during the copy
        // cannot leave a truncated upload that the preview would read as a complete file.
        Path temporary = Files.createTempFile(rootDirectory, TEMPORARY_FILE_PREFIX, TEMPORARY_FILE_SUFFIX);
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveFile(temporary, target);
        } finally {
            deleteTemporaryFile(temporary);
        }
        return fileId;
    }

    @Override
    public Optional<StagedImportFile> find(String fileId) {
        if (!ImportStagingFiles.isFileId(fileId)) {
            return Optional.empty();
        }
        return readStagedFiles().stream().filter(file -> file.fileId().equals(fileId)).findFirst();
    }

    @Override
    public List<StagedImportFile> list() {
        return readStagedFiles();
    }

    @Override
    public Optional<StagedImportFile> delete(String fileId) {
        if (!ImportStagingFiles.isFileId(fileId)) {
            return Optional.empty();
        }
        Optional<StagedImportFile> stored = find(fileId);
        try {
            if (stored.isPresent()) {
                Files.deleteIfExists(stored.get().path());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete the staged import file " + fileId, e);
        }
        // A released upload must not stay claimed, otherwise the index keeps protecting a file that
        // is no longer there.
        forgetClaim(fileId);
        return stored;
    }

    @Override
    public boolean saveClaim(String fileId, Instant claimedAt) {
        if (!ImportStagingFiles.isFileId(fileId) || claimedAt == null) {
            return false;
        }
        indexLock.lock();
        try {
            Map<String, Long> claims = new TreeMap<>(readOwnIndexClaims());
            claims.put(fileId, claimedAt.toEpochMilli());
            return writeOwnIndex(claims);
        } finally {
            indexLock.unlock();
        }
    }

    /**
     * The claims in force right now: the ones of this process and the ones of every other process
     * that is still running. A claim of a process that is gone is not in force, whatever its index
     * file still says.
     */
    @Override
    public Map<String, Instant> claims() {
        indexLock.lock();
        try {
            Map<String, Instant> claims = new HashMap<>();
            for (Path indexFile : listIndexFiles()) {
                if (!isOwnIndexFile(indexFile) && !hasLiveOwner(indexFile)) {
                    continue;
                }
                readIndex(indexFile).ifPresent(index -> index.claims()
                        .forEach((fileId, claimedAt) -> claims.merge(fileId, Instant.ofEpochMilli(claimedAt),
                                (first, second) -> first.isAfter(second) ? first : second)));
            }
            return claims;
        } finally {
            indexLock.unlock();
        }
    }

    /**
     * One pass over the legacy location: every stored upload moves to the staging directory, and an
     * upload that cannot be moved stays where it was with a warning.
     */
    @Override
    public int migrateLegacyFiles() {
        if (sameDirectory(legacyDirectory, rootDirectory) || !Files.isDirectory(legacyDirectory)) {
            return 0;
        }
        List<Path> legacyFiles = legacyStagingFiles();
        if (legacyFiles.isEmpty()) {
            return 0;
        }
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException e) {
            log.warn("Keeping {} legacy import staging file(s) in {}: the staging directory is not writable",
                    legacyFiles.size(), legacyDirectory, e);
            return 0;
        }
        int moved = 0;
        for (Path file : legacyFiles) {
            Path target = rootDirectory.resolve(file.getFileName().toString());
            if (Files.exists(target)) {
                // Two different uploads never share an identifier, so this only happens when the same
                // file was already stored here; the stored upload wins and nothing is lost.
                log.warn("Keeping the legacy import staging file {} in {}: {} already exists", file,
                        legacyDirectory, target);
                continue;
            }
            try {
                moveFile(file, target);
                moved++;
                log.info("Moved the legacy import staging file {} to {}", file, target);
            } catch (IOException e) {
                log.warn("Failed to move the legacy import staging file {}, keeping it in {}", file, legacyDirectory,
                        e);
            }
        }
        log.info("Moved {} of {} legacy import staging file(s) into {}", moved, legacyFiles.size(), rootDirectory);
        return moved;
    }

    /**
     * Releases what the processes that are gone left behind and writes the index of this process.
     * Their uploads are not deleted: releasing the claim returns them to the unclaimed lifetime, so
     * the cleanup policy of the caller decides when they go.
     */
    void cleanupPreviousRun() {
        if (!Files.isDirectory(rootDirectory)) {
            return;
        }
        for (Path indexFile : listIndexFiles()) {
            if (isOwnIndexFile(indexFile) || hasLiveOwner(indexFile)) {
                continue;
            }
            int adopted = readIndex(indexFile).map(index -> index.claims().size()).orElse(0);
            if (!deleteIndexFile(indexFile)) {
                continue;
            }
            log.info("Released {} claim(s) of the process {} that is gone and removed its index {}", adopted,
                    pidOf(indexFile), indexFile);
        }
        // The own index is written from scratch, so a leftover of an earlier run with the same pid
        // can never keep a claim alive.
        indexLock.lock();
        try {
            writeOwnIndex(Map.of());
        } finally {
            indexLock.unlock();
        }
    }

    /** Every stored upload in the staging directory, oldest first. */
    private List<StagedImportFile> readStagedFiles() {
        if (!Files.isDirectory(rootDirectory)) {
            return List.of();
        }
        Map<String, Instant> claims = claims();
        List<StagedImportFile> stored = new ArrayList<>();
        try (Stream<Path> entries = Files.list(rootDirectory)) {
            entries.map(entry -> readStagedFile(entry, claims)).flatMap(Optional::stream).forEach(stored::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the import staging directory " + rootDirectory, e);
        }
        stored.sort(Comparator.comparing(StagedImportFile::lastModified).thenComparing(StagedImportFile::fileId));
        return stored;
    }

    /**
     * One directory entry as a stored upload. Anything that is not a readable regular file inside the
     * staging directory reads as empty, so a process index, a temporary file, and a link that leaves
     * the directory are never handed out as an upload.
     */
    private Optional<StagedImportFile> readStagedFile(Path entry, Map<String, Instant> claims) {
        Path fileName = entry.getFileName();
        if (fileName == null || !ImportStagingFiles.isStagedFileName(fileName.toString())) {
            return Optional.empty();
        }
        try {
            if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(entry)) {
                return Optional.empty();
            }
            Path file = entry.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!belongsToRoot(file)) {
                return Optional.empty();
            }
            String name = fileName.toString();
            String fileId = ImportStagingFiles.fileIdOf(name);
            return Optional.of(new StagedImportFile(fileId, file, ImportStagingFiles.extensionOf(name),
                    Files.size(file), Files.getLastModifiedTime(file).toInstant(), claims.get(fileId)));
        } catch (IOException e) {
            log.warn("Skipping the unreadable import staging entry {}", entry, e);
            return Optional.empty();
        }
    }

    /**
     * Whether a resolved file really is a direct child of the staging directory. Two spellings of the
     * same directory compare unequal when a path component is a symlink, so the file identity decides
     * it. The resolved directory is expanded as well, because the resolved file always is.
     */
    private boolean belongsToRoot(Path file) throws IOException {
        if (!Files.isDirectory(rootDirectory)) {
            return false;
        }
        Path directory = rootDirectory.toRealPath();
        Path parent = file.getParent();
        return parent != null && (parent.equals(directory) || Files.isSameFile(parent, directory));
    }

    /** Forgets one claim of this process; an upload nobody claims has to be readable as such. */
    private void forgetClaim(String fileId) {
        indexLock.lock();
        try {
            Map<String, Long> claims = new TreeMap<>(readOwnIndexClaims());
            if (claims.remove(fileId) != null) {
                writeOwnIndex(claims);
            }
        } finally {
            indexLock.unlock();
        }
    }

    private Path ownIndexFile() {
        return rootDirectory.resolve(INDEX_FILE_PREFIX + ownPid + INDEX_FILE_SUFFIX);
    }

    private boolean isOwnIndexFile(Path indexFile) {
        return indexFile.getFileName().toString().equals(ownIndexFile().getFileName().toString());
    }

    /** Every index file in the staging directory, own one included. */
    private List<Path> listIndexFiles() {
        if (!Files.isDirectory(rootDirectory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(rootDirectory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> isIndexFileName(path.getFileName().toString())).sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Failed to scan the import staging indexes in {}", rootDirectory, e);
            return List.of();
        }
    }

    static boolean isIndexFileName(String fileName) {
        return fileName.startsWith(INDEX_FILE_PREFIX) && fileName.endsWith(INDEX_FILE_SUFFIX);
    }

    /** @return whether the process that wrote this index is still running, so its claims are in force */
    private boolean hasLiveOwner(Path indexFile) {
        long pid = pidOf(indexFile);
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** @return the pid an index file is named after, or {@code -1} when the name carries none */
    private long pidOf(Path indexFile) {
        String name = indexFile.getFileName().toString();
        String pid = name.substring(INDEX_FILE_PREFIX.length(), name.length() - INDEX_FILE_SUFFIX.length());
        try {
            return Long.parseLong(pid);
        } catch (NumberFormatException e) {
            // A name this store never writes: it can only come from a process that is gone.
            return -1;
        }
    }

    /** The claims this process recorded, or none when its index cannot be read. */
    private Map<String, Long> readOwnIndexClaims() {
        return readIndex(ownIndexFile()).map(ProcessIndex::claims).orElseGet(Map::of);
    }

    /**
     * Reads one index. A damaged index is reported and reads as absent: its claims are then treated
     * as released, which is the direction that cannot keep an upload alive forever.
     */
    private Optional<ProcessIndex> readIndex(Path indexFile) {
        try {
            String content = Files.readString(indexFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(content, ProcessIndex.class));
        } catch (IOException | RuntimeException e) {
            log.warn("Treating the unreadable import staging index {} as the index of a process that is gone",
                    indexFile, e);
            return Optional.empty();
        }
    }

    /**
     * Rewrites this process's index atomically. The index is an optimisation for the claims of a
     * process that is still running, so a failure only degrades the protection of its uploads and is
     * reported to the caller instead of thrown.
     */
    private boolean writeOwnIndex(Map<String, Long> claims) {
        Path indexFile = ownIndexFile();
        try {
            writeAtomically(indexFile,
                    objectMapper.writeValueAsBytes(new ProcessIndex(ownPid, startedAt, claims)));
            return true;
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise the import staging index {}", indexFile, e);
            return false;
        } catch (IOException e) {
            log.warn("Failed to update the import staging index {}", indexFile, e);
            return false;
        }
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID()
                + TEMPORARY_FILE_SUFFIX);
        try {
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveFileReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean deleteIndexFile(Path indexFile) {
        try {
            Files.deleteIfExists(indexFile);
            return true;
        } catch (IOException e) {
            log.warn("Failed to delete the stale import staging index {}", indexFile, e);
            return false;
        }
    }

    /** Moves one file in a single step; a filesystem without atomic rename falls back. */
    private void moveFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void moveFileReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Resolves one file name inside the staging directory and refuses to leave it. */
    Path resolveStagedFile(String fileId, String extension) {
        if (!ImportStagingFiles.isFileId(fileId) || !ImportStagingFiles.isAllowedExtension(extension)) {
            throw new IllegalArgumentException("Unsupported import staging file name: " + fileId + "." + extension);
        }
        Path file = rootDirectory.resolve(ImportStagingFiles.fileName(fileId, extension)).normalize();
        if (!rootDirectory.equals(file.getParent())) {
            throw new IllegalArgumentException("Import staging file name leaves its directory: " + fileId);
        }
        return file;
    }

    /** Every stored upload below the legacy location, in a stable order. */
    private List<Path> legacyStagingFiles() {
        try (Stream<Path> tree = Files.walk(legacyDirectory)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> {
                        Path fileName = path.getFileName();
                        return fileName != null && ImportStagingFiles.isStagedFileName(fileName.toString());
                    })
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Failed to list the legacy import staging files in {}", legacyDirectory, e);
            return List.of();
        }
    }

    /**
     * A crash during an upload leaves a temporary file that no other pass removes: it is not an
     * upload, so the listing and the total budget never see it.
     */
    private void deleteTemporaryFiles() {
        if (!Files.isDirectory(rootDirectory)) {
            return;
        }
        try (Stream<Path> entries = Files.list(rootDirectory)) {
            entries.filter(this::isUploadTemporaryFile).forEach(this::deleteTemporaryFile);
        } catch (IOException e) {
            log.warn("Failed to sweep the temporary import staging files in {}", rootDirectory, e);
        }
    }

    private boolean isUploadTemporaryFile(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return name.startsWith(TEMPORARY_FILE_PREFIX) && name.endsWith(TEMPORARY_FILE_SUFFIX)
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private void deleteTemporaryFile(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException e) {
            log.warn("Failed to clean up the temporary import staging file {}", temporary, e);
        }
    }

    private boolean sameDirectory(Path first, Path second) {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
    }

    /**
     * The claims of one process, as stored in its own {@code index-<pid>.json}: the process, when it
     * started writing the index, and the uploads it claimed with the moment it claimed them.
     */
    record ProcessIndex(long pid, long startedAt, Map<String, Long> claims) {

        ProcessIndex {
            claims = claims == null ? Map.of() : Map.copyOf(claims);
        }
    }
}

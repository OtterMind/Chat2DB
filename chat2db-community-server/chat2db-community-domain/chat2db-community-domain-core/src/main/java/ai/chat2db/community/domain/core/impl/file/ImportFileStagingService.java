package ai.chat2db.community.domain.core.impl.file;

import ai.chat2db.community.domain.api.model.file.StagedImportFile;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingStore;
import ai.chat2db.community.domain.api.service.file.ImportStagingFiles;
import ai.chat2db.community.tools.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns server-side import uploads between the HTTP request and asynchronous task execution.
 *
 * The service owns the policy: which uploads are accepted, how long an unclaimed upload may stay, how
 * long a claim protects it, how much disk the staging directory may use, and which upload to drop when
 * it uses more. Where an upload is written, how it is named, and how a claim is kept belongs to
 * {@link IImportFileStagingStore}.
 *
 * The stale uploads are dropped when the application starts and then once an hour, not only on the
 * next import: a staging directory nobody uses any more still empties itself. A claim is read from the
 * store, so it survives a restart and a claim of a process that is gone no longer hides an upload from
 * the lifetime that applies to it.
 */
@Component
@Slf4j
public class ImportFileStagingService implements IImportFileStagingService {

    /** An upload nobody claimed is dropped once it is this old. */
    static final Duration MAX_AGE = Duration.ofHours(24);

    /** An upload a task claimed is protected for this long, whatever happened to that task. */
    static final Duration CLAIMED_MAX_AGE = Duration.ofDays(7);

    static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    /** The shipped staging budget, {@code chat2db.import.staging.max-total-bytes} overrides it. */
    static final long DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    private static final String ERROR_UNREADABLE = "import.preview.fileUnreadable";
    private static final String ERROR_TOO_LARGE = "import.preview.fileTooLarge";
    private static final String REASON_RELEASED = "released by the task";
    private static final String REASON_OVER_BUDGET = "over the staging budget";

    private final IImportFileStagingStore stagingStore;

    private final Duration maxAge;

    private final Duration claimedMaxAge;

    private final long maxTotalBytes;

    /** The legacy migration runs at most once per instance, whatever the entry point. */
    private final AtomicBoolean legacyMigrationDone = new AtomicBoolean();

    @Autowired
    public ImportFileStagingService(IImportFileStagingStore stagingStore,
            @Value("${chat2db.import.staging.max-total-bytes:2147483648}") long maxTotalBytes) {
        this(stagingStore, maxTotalBytes, MAX_AGE, CLAIMED_MAX_AGE);
    }

    /**
     * Test seam: the shipped lifetimes are constants, and only a caller that supplies shorter ones can
     * exercise the boundaries without waiting a day.
     */
    ImportFileStagingService(IImportFileStagingStore stagingStore, long maxTotalBytes, Duration maxAge,
            Duration claimedMaxAge) {
        this.stagingStore = stagingStore;
        this.maxTotalBytes = maxTotalBytes;
        this.maxAge = maxAge;
        this.claimedMaxAge = claimedMaxAge;
    }

    /** Uploads of a previous run are adopted and abandoned ones dropped as soon as the application starts. */
    @PostConstruct
    void prepareStagingOnStartup() {
        migrateLegacyFilesOnce();
        cleanupStagingFiles();
    }

    /**
     * Drops what may go: everything unclaimed past its lifetime, everything claimed past the claim
     * lifetime, and the oldest uploads while the directory is over its budget. The startup pass runs
     * again every hour, so a staging directory nobody uses still empties.
     */
    @Scheduled(fixedDelayString = "${chat2db.import.staging.cleanup-interval-ms:3600000}")
    public void cleanupStagingFiles() {
        try {
            deleteExpiredFiles();
            deleteFilesOverBudget();
        } catch (RuntimeException e) {
            log.warn("Failed to clean the import staging directory", e);
        }
    }

    @Override
    public String stage(File file, String originalFileName) {
        Path source = validateSource(file, originalFileName);
        migrateLegacyFilesOnce();
        cleanupStagingFiles();
        String extension = ImportStagingFiles.extensionOf(originalFileName);
        try {
            return stagingStore.store(source, extension);
        } catch (IOException e) {
            throw new BusinessException(ERROR_UNREADABLE, null, e);
        }
    }

    @Override
    public File resolve(String fileId) {
        // The identifier shape is checked before the file system is touched, so a crafted identifier
        // can never name a path outside the staging directory.
        if (!ImportStagingFiles.isFileId(fileId)) {
            throw new BusinessException(ERROR_UNREADABLE);
        }
        migrateLegacyFilesOnce();
        cleanupStagingFiles();
        try {
            return stagingStore.find(fileId)
                    .map(StagedImportFile::path)
                    .map(Path::toFile)
                    .orElseThrow(() -> new BusinessException(ERROR_UNREADABLE));
        } catch (UncheckedIOException e) {
            throw new BusinessException(ERROR_UNREADABLE, null, e);
        }
    }

    @Override
    public void claimForTask(String fileId) {
        resolve(fileId);
        if (!stagingStore.saveClaim(fileId, Instant.now())) {
            // The upload is then protected by the unclaimed lifetime alone, so a task that runs
            // longer than that could lose its source file after a restart.
            log.warn("The claim of the staged import file {} is not recorded in the process index", fileId);
        }
    }

    @Override
    public void release(String fileId) {
        if (!ImportStagingFiles.isFileId(fileId)) {
            return;
        }
        try {
            stagingStore.delete(fileId).ifPresent(file -> logDeletion(file, REASON_RELEASED));
        } catch (RuntimeException e) {
            // Cleanup does not change the task outcome once execution has completed.
            log.warn("Failed to delete the staged import file {} after the task completed", fileId, e);
        }
    }

    /**
     * Drops every upload whose lifetime is over. An upload nobody claims lives {@link #MAX_AGE}; a
     * claim protects it for {@link #CLAIMED_MAX_AGE} after the moment it was claimed.
     */
    private void deleteExpiredFiles() {
        Instant now = Instant.now();
        Instant unclaimedDeadline = now.minus(maxAge);
        Instant claimedDeadline = now.minus(claimedMaxAge);
        for (StagedImportFile file : stagingStore.list()) {
            if (!file.lastModified().isBefore(unclaimedDeadline)) {
                continue;
            }
            Instant claimedAt = file.claimedAt();
            if (claimedAt == null) {
                deleteStagedFile(file, "unclaimed for more than " + lifetime(maxAge));
            } else if (claimedAt.isBefore(claimedDeadline)) {
                deleteStagedFile(file, "claimed more than " + lifetime(claimedMaxAge) + " ago");
            }
        }
    }

    /**
     * Drops the oldest uploads until the staging directory fits its budget. A claim that is still in
     * force is never dropped: an upload a running task reads must stay readable even when the
     * directory is over its budget.
     */
    private void deleteFilesOverBudget() {
        List<StagedImportFile> files = stagingStore.list();
        long total = files.stream().mapToLong(StagedImportFile::size).sum();
        if (total <= maxTotalBytes) {
            return;
        }
        Instant claimedDeadline = Instant.now().minus(claimedMaxAge);
        List<StagedImportFile> dropped = new ArrayList<>();
        long freed = 0L;
        for (StagedImportFile file : files) {
            if (total - freed <= maxTotalBytes) {
                break;
            }
            Instant claimedAt = file.claimedAt();
            if (claimedAt != null && !claimedAt.isBefore(claimedDeadline)) {
                continue;
            }
            if (deleteStagedFile(file, REASON_OVER_BUDGET)) {
                dropped.add(file);
                freed += file.size();
            }
        }
        if (!dropped.isEmpty()) {
            log.info("Dropped {} staged import file(s) over the {} byte budget, freeing {} byte(s): {}", dropped.size(),
                    maxTotalBytes, freed, dropped.stream().map(file -> file.path().getFileName().toString()).toList());
        }
    }

    private boolean deleteStagedFile(StagedImportFile file, String reason) {
        try {
            stagingStore.delete(file.fileId());
            logDeletion(file, reason);
            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to delete the staged import file {} ({})", file.path(), reason, e);
            return false;
        }
    }

    /** A lifetime in the unit it reads best in, so a short test lifetime does not log "0 d". */
    private static String lifetime(Duration lifetime) {
        if (lifetime.toDays() > 0) {
            return lifetime.toDays() + " d";
        }
        if (lifetime.toHours() > 0) {
            return lifetime.toHours() + " h";
        }
        return lifetime.toMinutes() + " min";
    }

    /** Every removal is reported: the staging directory of a running product must be explainable. */
    private void logDeletion(StagedImportFile file, String reason) {
        log.info("Deleted the staged import file {} ({} byte(s), {})", file.path(), file.size(), reason);
    }

    private void migrateLegacyFilesOnce() {
        if (!legacyMigrationDone.compareAndSet(false, true)) {
            return;
        }
        try {
            int moved = stagingStore.migrateLegacyFiles();
            if (moved > 0) {
                log.info("Adopted {} legacy import staging file(s) into the workspace storage directory", moved);
            }
        } catch (RuntimeException e) {
            log.warn("Skipping the legacy import staging migration", e);
        }
    }

    private static Path validateSource(File file, String originalFileName) {
        if (file == null
                || !ImportStagingFiles.isAllowedExtension(ImportStagingFiles.extensionOf(originalFileName))) {
            throw new BusinessException(ERROR_UNREADABLE);
        }
        try {
            Path source = file.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(source)) {
                throw new BusinessException(ERROR_UNREADABLE);
            }
            if (Files.size(source) > MAX_SIZE_BYTES) {
                throw new BusinessException(ERROR_TOO_LARGE, new Object[]{MAX_SIZE_BYTES / (1024 * 1024)});
            }
            return source;
        } catch (IOException | InvalidPathException e) {
            throw new BusinessException(ERROR_UNREADABLE, null, e);
        }
    }
}

package ai.chat2db.community.domain.api.model.file;

import java.nio.file.Path;
import java.time.Instant;

/**
 * One import upload waiting in the workspace staging directory.
 *
 * @param fileId       the opaque identifier the upload was accepted under
 * @param path         the file on disk, already resolved inside the staging directory
 * @param extension    the lower-case extension the upload was stored with
 * @param size         the stored size in bytes, used by the total staging budget
 * @param lastModified the moment the upload was stored; the unclaimed lifetime is measured from it
 * @param claimedAt    when an asynchronous task took ownership, or {@code null} while nobody owns it
 */
public record StagedImportFile(String fileId, Path path, String extension, long size, Instant lastModified,
        Instant claimedAt) {

    /** Whether an asynchronous task owns this upload. */
    public boolean isClaimed() {
        return claimedAt != null;
    }
}

package ai.chat2db.community.domain.api.service.file;

import java.io.File;

/**
 * Stores browser uploads under opaque server-controlled IDs until an import task finishes.
 */
public interface IImportFileStagingService {

    /** Copies a validated upload into server-managed staging and returns its opaque ID. */
    String stage(File file, String originalFileName);

    /** Resolves an opaque ID to its server-controlled file. */
    File resolve(String fileId);

    /** Protects a staged file from normal expiry cleanup while an asynchronous task owns it. */
    void claimForTask(String fileId);

    /** Deletes a staged file after task completion or failed submission. */
    void release(String fileId);
}

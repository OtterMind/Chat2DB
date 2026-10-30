package ai.chat2db.community.domain.api.service.file;

import ai.chat2db.community.domain.api.model.file.StagedImportFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the import uploads of the running product on disk until their import task finishes.
 *
 * The store owns the physical layout: an upload lives in
 * {@code <product state directory>/<env>/storage/import-staging/<identifier>.<extension>}, an upload
 * is written through a temporary file so a crash never leaves a half-written upload behind, and an
 * upload is only ever resolved by its identifier inside the staging directory, so no caller can
 * reach anything else through this contract. The uploads written to the location that predates the
 * workspace storage directory are migrated into it.
 *
 * What an upload is worth, how long it may stay, and which of several candidates to drop is the
 * caller's policy: the store reports what is stored, including when a task claimed an upload, and
 * performs the removals the caller asks for.
 *
 * A claim is kept in a process index, {@code index-<pid>.json} next to the uploads, so it is not
 * lost with the heap of the process that recorded it. A caller reads the claims that are in force
 * rather than the claims of this process alone: an upload claimed by a process that is gone is
 * unclaimed again, and the stale index is removed while the upload stays.
 */
public interface IImportFileStagingStore {

    /**
     * Copies one readable source file into the staging directory under a fresh identifier.
     *
     * @param extension the lower-case extension the upload is stored with, already validated by the caller
     * @return the identifier the upload is stored under
     * @throws IOException when the upload cannot be written
     */
    String store(Path source, String extension) throws IOException;

    /**
     * The upload stored under one identifier. An identifier that is not an identifier at all, an
     * upload that is gone, and an entry that leaves the staging directory all read as empty.
     */
    Optional<StagedImportFile> find(String fileId);

    /** Every stored upload, oldest first. */
    List<StagedImportFile> list();

    /**
     * Removes one upload together with its claim.
     *
     * @return the upload that was removed, or empty when nothing was stored under this identifier
     * @throws java.io.UncheckedIOException when a stored upload exists but cannot be removed
     */
    Optional<StagedImportFile> delete(String fileId);

    /**
     * Records that an asynchronous task took ownership of one upload. The claim is written to this
     * process's index, so a restart of the process does not silently turn a claimed upload into an
     * expired one while it is being read.
     *
     * @return whether the claim was persisted; a caller that cannot persist it has to report that
     *         the upload is then only protected by the unclaimed lifetime
     */
    boolean saveClaim(String fileId, Instant claimedAt);

    /**
     * The claims in force right now: the ones of this process and the ones of every other process
     * that is still running. A claim of a process that is gone is not in force, so its uploads are
     * unclaimed again and the caller's lifetime decides about them.
     */
    Map<String, Instant> claims();

    /**
     * Moves the uploads written to the location that predates the workspace storage directory into
     * the staging directory, keeping the legacy directory itself. The migration is idempotent, never
     * overwrites a stored upload, and leaves an upload it cannot move where it was.
     *
     * @return how many uploads were moved
     */
    int migrateLegacyFiles();
}

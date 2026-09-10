package ai.chat2db.community.domain.api.model.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Roles of task artifacts. Contract values are strings so new roles can be added without a shared
 * enum change.
 */
public final class TaskArtifactRole {

    public static final String OUTPUT = "OUTPUT";

    /** Legacy single-file reject output. Manifest imports suffix this role with the shard id. */
    public static final String REJECT = "REJECT";

    /** Machine-readable aggregate for all per-shard reject outputs. */
    public static final String REJECT_SUMMARY = "REJECT_SUMMARY";

    /** Post-import verification, finalization, rollback, and drill evidence. */
    public static final String IMPORT_REPORT = "IMPORT_REPORT";

    /**
     * Whether an artifact is diagnostic evidence that remains useful when a task does not
     * complete successfully. Ordinary output must never pass this check.
     */
    public static boolean isDiagnostic(String role) {
        String rejectPrefix = REJECT + ":";
        return IMPORT_REPORT.equals(role)
                || REJECT_SUMMARY.equals(role)
                || REJECT.equals(role)
                || role != null && role.startsWith(rejectPrefix) && role.length() > rejectPrefix.length();
    }

    /** Stable per-shard role that fits the task_artifact VARCHAR(32) storage contract. */
    public static String rejectForShard(String shardId) {
        if (shardId == null || shardId.isBlank()) {
            throw new IllegalArgumentException("Reject artifact shard id is required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(shardId.getBytes(StandardCharsets.UTF_8));
            return REJECT + ":" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private TaskArtifactRole() {
    }
}

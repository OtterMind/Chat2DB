package ai.chat2db.community.domain.api.model.task;

/**
 * Optional post-format compression for export artifacts. Contract values are strings so new
 * algorithms can be added without a shared enum change; only {@link #GZIP} is produced today.
 */
public final class TaskCompression {

    public static final String GZIP = "GZIP";

    private TaskCompression() {
    }
}

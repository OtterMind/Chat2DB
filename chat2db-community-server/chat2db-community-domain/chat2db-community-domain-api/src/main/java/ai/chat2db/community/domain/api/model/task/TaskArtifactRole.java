package ai.chat2db.community.domain.api.model.task;

/**
 * Roles of task artifacts. Contract values are strings so new roles can be added without a shared
 * enum change; only {@link #OUTPUT} is produced today.
 */
public final class TaskArtifactRole {

    public static final String OUTPUT = "OUTPUT";

    private TaskArtifactRole() {
    }
}

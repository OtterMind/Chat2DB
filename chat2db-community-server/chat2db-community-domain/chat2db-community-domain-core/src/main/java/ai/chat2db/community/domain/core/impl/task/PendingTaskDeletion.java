package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.Task;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
class PendingTaskDeletion {
    private Long taskId;
    private String originalPath;
    private String stagedPath;
    private int attempts;
    private String lastError;

    static PendingTaskDeletion create(Task task) {
        PendingTaskDeletion deletion = new PendingTaskDeletion();
        deletion.taskId = task.getId();
        if (StringUtils.isNotBlank(task.getArtifactId())) {
            Path original = Path.of(task.getArtifactId()).toAbsolutePath().normalize();
            deletion.originalPath = original.toString();
            deletion.stagedPath = original.resolveSibling("." + original.getFileName()
                    + ".task-delete-" + UUID.randomUUID()).toString();
        }
        return deletion;
    }

    void beginAttempt() {
        attempts++;
        lastError = null;
    }

    void recordFailure(Exception error) {
        lastError = error.toString();
    }

    boolean hasArtifact() {
        return stagedPath != null;
    }

    Path originalFile() {
        return Path.of(originalPath);
    }

    Path stagedFile() {
        return Path.of(stagedPath);
    }
}

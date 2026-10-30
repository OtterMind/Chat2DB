package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import com.alibaba.fastjson2.JSON;
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
    /** Rejected-rows file of an import task, removed together with the primary artifact. */
    private String errorArtifactPath;
    private String stagedErrorArtifactPath;
    /** Staged import upload of the task, so deleting the task also frees the source file. */
    private String importFileId;
    private int attempts;
    private String lastError;

    static PendingTaskDeletion create(Task task) {
        PendingTaskDeletion deletion = new PendingTaskDeletion();
        deletion.taskId = task.getId();
        if (StringUtils.isNotBlank(task.getArtifactId())) {
            Path original = Path.of(task.getArtifactId()).toAbsolutePath().normalize();
            deletion.originalPath = original.toString();
            deletion.stagedPath = stagedPath(original);
        }
        if (StringUtils.isNotBlank(task.getErrorArtifactId())) {
            Path errorArtifact = Path.of(task.getErrorArtifactId()).toAbsolutePath().normalize();
            deletion.errorArtifactPath = errorArtifact.toString();
            deletion.stagedErrorArtifactPath = stagedPath(errorArtifact);
        }
        deletion.importFileId = importFileId(task);
        return deletion;
    }

    private static String stagedPath(Path original) {
        return original.resolveSibling("." + original.getFileName()
                + ".task-delete-" + UUID.randomUUID()).toString();
    }

    /** The staged upload the task imports; null for exports and for tasks without a stored spec. */
    private static String importFileId(Task task) {
        if (StringUtils.isBlank(task.getSpecJson())) {
            return null;
        }
        try {
            ImportTaskSpec spec = JSON.parseObject(task.getSpecJson(), ImportTaskSpec.class);
            return spec == null ? null : spec.getImportFileId();
        } catch (RuntimeException ignored) {
            // A spec that cannot be read only means the staged file expires on its own.
            return null;
        }
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

    boolean hasErrorArtifact() {
        return stagedErrorArtifactPath != null;
    }

    Path originalFile() {
        return Path.of(originalPath);
    }

    Path stagedFile() {
        return Path.of(stagedPath);
    }

    Path errorArtifactFile() {
        return Path.of(errorArtifactPath);
    }

    Path stagedErrorArtifactFile() {
        return Path.of(stagedErrorArtifactPath);
    }
}

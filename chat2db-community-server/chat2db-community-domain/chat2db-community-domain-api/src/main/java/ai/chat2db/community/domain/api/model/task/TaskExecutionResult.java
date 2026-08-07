package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionResult {

    private ArtifactDraft artifactDraft;

    private String summary;

    public static TaskExecutionResult completed() {
        return new TaskExecutionResult(null, null);
    }

    public static TaskExecutionResult withArtifact(ArtifactDraft artifactDraft) {
        return new TaskExecutionResult(artifactDraft, null);
    }
}

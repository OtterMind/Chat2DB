package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * A published output of a task. The primary download keeps {@code Task.artifactId} and is recorded
 * here with role {@link TaskArtifactRole#OUTPUT}; additional products such as reject files are
 * recorded as sibling rows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskArtifact {

    private String artifactId;

    private String role;

    private String mediaType;

    private Long sizeBytes;

    private Date createdAt;
}

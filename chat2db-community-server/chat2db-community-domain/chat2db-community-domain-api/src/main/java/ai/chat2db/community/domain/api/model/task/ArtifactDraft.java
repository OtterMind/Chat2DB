package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.File;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactDraft {

    private File temporaryFile;

    private File targetFile;

    private String mediaType;
}

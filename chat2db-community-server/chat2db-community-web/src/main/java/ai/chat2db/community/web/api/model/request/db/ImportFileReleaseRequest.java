package ai.chat2db.community.web.api.model.request.db;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Identifies an abandoned, unclaimed import preview upload. */
@Data
public class ImportFileReleaseRequest {

    @NotBlank
    private String fileId;
}

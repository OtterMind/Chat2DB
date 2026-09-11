package ai.chat2db.community.web.api.model.request.db;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DesktopImportFileRequest {

    @NotBlank
    private String sourceFile;

    @NotBlank
    private String originalFileName;
}

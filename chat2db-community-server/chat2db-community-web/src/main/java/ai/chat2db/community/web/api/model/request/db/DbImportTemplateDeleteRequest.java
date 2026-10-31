package ai.chat2db.community.web.api.model.request.db;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Deletes the global import template with this name. */
@Data
public class DbImportTemplateDeleteRequest {

    @NotBlank
    private String name;

    /**
     * File type of the template, used to find its file when no readable document carries the name.
     * Optional: the name alone already finds every template the store can read.
     */
    private String fileType;
}

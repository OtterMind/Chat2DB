package ai.chat2db.community.web.api.model.response.db;

import lombok.Data;

import java.util.Map;

/** One global import format preset as the wizard consumes it. */
@Data
public class DbImportTemplateResponse {

    private String name;

    private Long createdAt;

    /** File type the preset belongs to; null when the stored document does not say. */
    private String fileType;

    private Map<String, Object> formatOptions;
}

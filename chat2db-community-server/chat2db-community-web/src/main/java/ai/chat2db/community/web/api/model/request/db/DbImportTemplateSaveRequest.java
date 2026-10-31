package ai.chat2db.community.web.api.model.request.db;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/** Saves one global import format preset; an existing template with the same name is replaced. */
@Data
public class DbImportTemplateSaveRequest {

    /**
     * Template name. Blank, over-long, and unusable names are rejected by the template service with
     * their own message codes, so only the presence of the field is checked here.
     */
    @NotNull
    private String name;

    private Long createdAt;

    /**
     * File type the preset belongs to, named after {@code TaskFileFormat} ({@code CSV},
     * {@code XLS}, {@code XLSX}, or {@code JSON}). A request that omits it still gets the type its
     * format options imply.
     */
    private String fileType;

    /** The complete format option group the wizard had on its format step, keyed by file format. */
    private Map<String, Object> formatOptions;
}

package ai.chat2db.community.web.api.model.request.db;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StructureDiffRequest {
    @Valid
    @NotNull
    private StructureInfo source;
    @Valid
    @NotNull
    private StructureInfo target;

    @Data
    public static class StructureInfo {
        @NotNull
        private Long dataSourceId;
        @NotBlank
        private String databaseName;
        private String schemaName;
    }
}

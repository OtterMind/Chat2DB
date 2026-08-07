package ai.chat2db.community.domain.api.model.db;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DatabaseObjectDeletePrepare {

    private String confirmName;

    private String sqlPreview;

    private String objectType;

    private String dbType;

    /**
     * Objects occupying/depending on the target, surfaced before execution so the UI can block
     * deletion and list them. Populated for non-empty InnoDB General Tablespaces (each entry is a
     * qualified {@code schema.table}); {@code null} for databases/schemas (cascaded by the DB).
     */
    private List<String> occupyingTables;
}


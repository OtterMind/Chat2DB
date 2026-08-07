package ai.chat2db.community.web.api.model.response.db;

import java.util.List;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseObjectDeletePrepareResponse {

    private String confirmName;

    private String sqlPreview;

    private String objectType;

    private String dbType;

    /**
     * Objects occupying the target (qualified {@code schema.table} for a non-empty tablespace);
     * {@code null} for databases/schemas.
     */
    private List<String> occupyingTables;
}


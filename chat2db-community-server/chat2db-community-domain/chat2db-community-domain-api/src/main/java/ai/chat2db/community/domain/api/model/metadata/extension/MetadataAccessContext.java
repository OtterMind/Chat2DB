package ai.chat2db.community.domain.api.model.metadata.extension;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataAccessContext {

    private Long dataSourceId;

    private String dbType;

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String columnName;

    @Builder.Default
    private String operationType = "SELECT";
}

package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskTargetSnapshot {

    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private String tableName;
}

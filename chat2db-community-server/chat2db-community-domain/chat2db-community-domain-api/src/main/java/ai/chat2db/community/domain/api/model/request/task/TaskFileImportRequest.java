package ai.chat2db.community.domain.api.model.request.task;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TaskFileImportRequest {

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String importType;

    private String fileName;

    /** SQL-file import options (MYSQL-IMPORT-004). */
    private String encoding;
    private String errorPolicy;
    private String commitMode;
    private Integer batchSize;
}

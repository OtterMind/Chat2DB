package ai.chat2db.community.web.api.model.request.task;

import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import lombok.Data;

@Data
public class TaskImportRequest extends DataSourceBaseRequest {

    private String taskType;

    private String taskName;

    private String tableName;

    private String sourceFile;

    private String displayFileName;

    private String format;

    private String dataTimeFormat;

    private ImportOptions options;
}

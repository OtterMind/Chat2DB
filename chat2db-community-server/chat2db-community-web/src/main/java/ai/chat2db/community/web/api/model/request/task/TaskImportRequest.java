package ai.chat2db.community.web.api.model.request.task;

import java.util.List;
import java.util.Map;

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

    private List<Map<String, String>> columnMappings;

    private String unmappedTarget;

    private Map<String, Object> importOptions;
}

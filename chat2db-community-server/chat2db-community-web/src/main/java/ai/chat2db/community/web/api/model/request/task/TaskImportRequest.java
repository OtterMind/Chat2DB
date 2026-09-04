package ai.chat2db.community.web.api.model.request.task;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TaskImportRequest extends DataSourceBaseRequest {

    private String taskType;

    private String taskName;

    private String tableName;

    private String sourceFile;

    private String displayFileName;

    private String format;

    private String dataTimeFormat;

    private CsvOptions csvOptions;

    private List<Map<String, String>> mappings;

    private String unmappedTarget;
}

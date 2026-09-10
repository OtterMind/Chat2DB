package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ImportPreviewRequest extends DataSourceBaseRequest {

    @NotBlank
    private String tableName;

    @NotBlank
    private String fileId;

    private CsvOptions csvOptions;
}

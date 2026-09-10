package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ImportExecuteRequest extends DataSourceBaseRequest {

    @NotBlank
    private String tableName;

    @NotBlank
    private String fileId;

    private CsvOptions csvOptions;

    private List<ImportColumnMapping> mappings;

    private UnmappedTargetStrategy unmappedTarget;
}

package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.model.task.ExcelOptions;
import ai.chat2db.community.domain.api.model.task.JsonOptions;
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

    private ExcelOptions excelOptions;

    private JsonOptions jsonOptions;

    private List<ImportColumnMapping> mappings;

    private UnmappedTargetStrategy unmappedTarget;

    private String mode;

    /**
     * Acknowledgement required before a ULTRA_FAST import may pass admission rule R1. The
     * frontend sends it for every parallel import, so the backend has to accept and forward it.
     */
    private Boolean confirmedNoStrongRelations;

}

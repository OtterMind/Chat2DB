package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Lists the worksheets of an already staged spreadsheet; no target table is involved yet. */
@Data
public class ImportSheetsRequest extends DataSourceBaseRequest {

    @NotBlank
    private String fileId;
}

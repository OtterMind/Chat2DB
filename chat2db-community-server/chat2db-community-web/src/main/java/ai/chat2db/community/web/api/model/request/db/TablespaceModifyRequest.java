package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TablespaceModifyRequest extends DataSourceBaseRequest {

    @NotBlank
    private String oldName;

    @NotBlank
    private String newName;
}

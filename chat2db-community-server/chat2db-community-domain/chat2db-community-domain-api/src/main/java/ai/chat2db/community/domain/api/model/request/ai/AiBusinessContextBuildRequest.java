package ai.chat2db.community.domain.api.model.request.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiBusinessContextBuildRequest {

    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private List<AiSelectedKnowledge> selectedKnowledge = new ArrayList<>();
}

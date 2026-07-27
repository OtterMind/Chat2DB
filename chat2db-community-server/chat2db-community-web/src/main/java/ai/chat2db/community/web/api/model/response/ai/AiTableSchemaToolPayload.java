package ai.chat2db.community.web.api.model.response.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiTableSchemaToolPayload {

    private String tableName;

    private String schema;
}

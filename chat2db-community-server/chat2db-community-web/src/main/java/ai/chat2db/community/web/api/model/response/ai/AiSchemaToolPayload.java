package ai.chat2db.community.web.api.model.response.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiSchemaToolPayload {

    private String name;

    private Boolean system;

    private String comment;
}

package ai.chat2db.community.web.api.model.response.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiDataSourceToolPayload {

    private Long id;

    private String name;

    private String type;

    private String env;
}

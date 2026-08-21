package ai.chat2db.community.domain.api.model.request.ai;

import lombok.Data;

@Data
public class AiSelectedKnowledge {

    private Long id;

    private String type;

    private String key;

    private String value;
}

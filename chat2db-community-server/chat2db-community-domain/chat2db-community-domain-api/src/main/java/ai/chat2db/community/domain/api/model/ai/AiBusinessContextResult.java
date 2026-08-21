package ai.chat2db.community.domain.api.model.ai;

import ai.chat2db.community.domain.api.model.request.ai.AiSelectedKnowledge;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiBusinessContextResult {

    private String structuredContext;

    private List<AiSelectedKnowledge> selectedKnowledge = new ArrayList<>();

    public static AiBusinessContextResult empty() {
        return new AiBusinessContextResult(null, List.of());
    }
}

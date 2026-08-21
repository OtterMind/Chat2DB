package ai.chat2db.community.web.api.model.request.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SelectedKnowledgeRequest {

    @NotNull
    private Long id;

    @NotBlank
    private String type;
}

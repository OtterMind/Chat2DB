package ai.chat2db.community.web.api.model.request.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiChatSessionRenameRequest {

    @NotBlank
    private String id;

    @NotBlank
    @Size(max = 50)
    private String title;
}

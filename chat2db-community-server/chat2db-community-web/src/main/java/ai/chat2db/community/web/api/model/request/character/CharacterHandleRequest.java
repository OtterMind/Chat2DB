package ai.chat2db.community.web.api.model.request.character;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data
public class CharacterHandleRequest {
    @NotBlank
    private String text;
}

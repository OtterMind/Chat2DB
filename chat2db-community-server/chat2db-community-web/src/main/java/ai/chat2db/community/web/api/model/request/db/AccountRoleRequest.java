package ai.chat2db.community.web.api.model.request.db;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AccountRoleRequest {

    @NotBlank
    private String user;

    @NotBlank
    private String host;
}

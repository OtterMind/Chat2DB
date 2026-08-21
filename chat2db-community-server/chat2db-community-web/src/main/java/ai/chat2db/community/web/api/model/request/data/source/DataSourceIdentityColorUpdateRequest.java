package ai.chat2db.community.web.api.model.request.data.source;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DataSourceIdentityColorUpdateRequest {

    @NotNull
    private Long id;

    private String identityColor;
}

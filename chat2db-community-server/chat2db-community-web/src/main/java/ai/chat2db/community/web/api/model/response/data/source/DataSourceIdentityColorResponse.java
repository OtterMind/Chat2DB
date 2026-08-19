package ai.chat2db.community.web.api.model.response.data.source;

import ai.chat2db.community.web.api.model.response.environment.SimpleEnvironmentResponse;
import lombok.Data;

@Data
public class DataSourceIdentityColorResponse {

    private Long id;

    private String identityColor;

    private Long environmentId;

    private SimpleEnvironmentResponse environment;
}

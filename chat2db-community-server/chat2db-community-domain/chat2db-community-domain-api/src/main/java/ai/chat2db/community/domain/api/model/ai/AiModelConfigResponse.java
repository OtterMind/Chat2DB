package ai.chat2db.community.domain.api.model.ai;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiModelConfigResponse {

    private String id;

    private String name;

    private String provider;

    private String model;

    private String agentApi;

    private String baseUrl;

    private String projectId;

    private String location;

    private Double temperature;

    private Integer maxTokens;

    private Boolean enabled;

    private Boolean defaultConfig;

    private Boolean hasApiKey;

    private String apiKeyMasked;

    private LocalDateTime gmtModified;
}

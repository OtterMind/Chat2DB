package ai.chat2db.community.domain.api.model.ai;

import lombok.Data;

@Data
public class AiRuntimeModel {

    private boolean systemPreset;

    private String provider;

    private String model;

    private String agentApi;

    private String apiKey;

    private String baseUrl;

    private String projectId;

    private String location;

    private Double temperature;

    private Integer maxTokens;
}

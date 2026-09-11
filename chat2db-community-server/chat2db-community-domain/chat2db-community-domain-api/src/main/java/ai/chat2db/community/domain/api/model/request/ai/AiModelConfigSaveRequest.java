package ai.chat2db.community.domain.api.model.request.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AiModelConfigSaveRequest {

    private String id;

    @NotBlank
    private String name;

    @NotBlank
    private String provider;

    @NotBlank
    private String model;

    @Pattern(regexp = "openai-completions|openai-responses|anthropic-messages|google-generative-ai")
    private String agentApi;

    private String apiKey;

    private String baseUrl;

    private String projectId;

    private String location;

    private Double temperature;

    private Integer maxTokens;

    private Boolean enabled = Boolean.TRUE;

    private Boolean defaultConfig = Boolean.FALSE;
}

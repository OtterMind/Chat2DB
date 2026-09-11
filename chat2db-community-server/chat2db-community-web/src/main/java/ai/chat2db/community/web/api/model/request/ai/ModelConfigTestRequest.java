package ai.chat2db.community.web.api.model.request.ai;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ModelConfigTestRequest {

    private String id;

    @NotNull
    private AiProviderEnum provider;

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
}

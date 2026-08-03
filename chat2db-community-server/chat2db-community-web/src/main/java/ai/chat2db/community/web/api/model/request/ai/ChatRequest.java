package ai.chat2db.community.web.api.model.request.ai;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.ChatAttachment;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAccessType;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.annotation.JSONField;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ChatRequest {


    @JSONField(serialize = false, deserialize = false)
    private ConsoleResult consoleResult;

    @NotBlank
    private String input;

    @Valid
    private List<ChatMessage> history = new ArrayList<>();

    @Valid
    private List<ChatAttachment> attachments = new ArrayList<>();


    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private String systemPrompt;


    private String questionType;

    private Boolean enableTools = Boolean.TRUE;


    private String sessionId;


    private String modelConfigId;


    private AiProviderEnum provider;

    private String model;

    private String apiKey;

    private String baseUrl;

    private String projectId;

    private String location;

    private Double temperature;

    private Integer maxTokens;

    /**
     * Access type for route dispatch. Null means API_KEY (existing Spring AI path).
     * SUBSCRIPTION selects the Codex app-server route when other gates pass.
     */
    private AiAccessType accessType;

    /**
     * Stable user-message id for the attempt journal. When blank, the subscription route
     * generates one; the API-key path ignores this field.
     */
    private String messageId;

    /** Provider-advertised reasoning strength selected for a subscription turn. */
    private String reasoningEffort;

    /**
     * Stable model identity returned by the subscription model catalog. The backend
     * still verifies the referenced model against its current authenticated snapshot;
     * this value is never treated as proof of provider entitlement by itself.
     */
    private String modelRefKey;
}

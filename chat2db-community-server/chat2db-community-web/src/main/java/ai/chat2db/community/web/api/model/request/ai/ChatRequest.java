package ai.chat2db.community.web.api.model.request.ai;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.ChatAttachment;
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

    @Valid
    private List<SelectedKnowledgeRequest> selectedKnowledge = new ArrayList<>();


    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private String databaseType;

    private String tableName;

    private String columnList;

    private String systemPrompt;


    private String questionType;

    private Boolean enableTools = Boolean.TRUE;

    private Boolean persistHistory = Boolean.TRUE;


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
}

package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.ai.AiModelCatalogItem;
import ai.chat2db.community.domain.api.model.ai.AiModelConfigResponse;
import ai.chat2db.community.domain.api.model.ai.AiModelOptionItem;
import ai.chat2db.community.domain.api.model.ai.ChatAttachment;
import ai.chat2db.community.domain.api.model.ai.ModelConfigTestResponse;
import ai.chat2db.community.domain.api.service.ai.IAiAttachmentParseService;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import ai.chat2db.community.domain.api.service.ai.IAiChatStreamService;
import ai.chat2db.community.domain.api.service.ai.IAiModelConfigService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.web.api.model.request.ai.AiChatSessionDeleteRequest;
import ai.chat2db.community.web.api.model.request.ai.AiChatSessionRenameRequest;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import ai.chat2db.community.web.api.model.request.ai.ParseLocalAttachmentRequest;
import ai.chat2db.community.web.api.model.request.ai.ModelConfigDeleteRequest;
import ai.chat2db.community.web.api.model.request.ai.ModelConfigSaveRequest;
import ai.chat2db.community.web.api.model.request.ai.ModelConfigTestRequest;
import ai.chat2db.community.web.api.model.response.ai.AiAttachmentResponse;
import ai.chat2db.community.web.api.model.response.ai.AiChatMessageResponse;
import ai.chat2db.community.web.api.model.response.ai.AiChatSessionResponse;
import ai.chat2db.community.web.api.converter.ai.ChatConverter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Exposes AI chat, model configuration, attachment parsing, and chat history endpoints.
 */
@RestController
@RequestMapping("/api/v3/ai")
public class AiChatController {

    private final IAiChatStreamService<ChatRequest, SseEmitter> aiChatStreamService;
    private final IAiModelConfigService aiModelConfigService;
    private final IAiChatHistoryService aiChatHistoryService;
    private final IAiAttachmentParseService<MultipartFile, ParseLocalAttachmentRequest, ChatAttachment> aiAttachmentParseService;
    private final ChatConverter chatConverter;
    private final IIdentityService identityService;

    public AiChatController(IAiChatStreamService<ChatRequest, SseEmitter> aiChatStreamService,
                          IAiModelConfigService aiModelConfigService,
                          IAiChatHistoryService aiChatHistoryService,
                          IAiAttachmentParseService<MultipartFile, ParseLocalAttachmentRequest, ChatAttachment> aiAttachmentParseService,
                          ChatConverter chatConverter,
                          IIdentityService identityService) {
        this.aiChatStreamService = aiChatStreamService;
        this.aiModelConfigService = aiModelConfigService;
        this.aiChatHistoryService = aiChatHistoryService;
        this.aiAttachmentParseService = aiAttachmentParseService;
        this.chatConverter = chatConverter;
        this.identityService = identityService;
    }

    /**
     * Handles stream for AI chat.
     * <p>
     * Endpoint: {@code POST /api/v3/ai/chat/stream}.
     *
     * @param request request payload or query parameters for the operation.
     * @return server-sent event stream for the request.
     */
    @PostMapping("/chat/stream")
    public SseEmitter stream(@RequestBody @Valid ChatRequest request) {
        return aiChatStreamService.stream(request);
    }

    /**
     * Handles parse uploaded attachment for AI chat.
     * <p>
     * Endpoint: {@code POST /api/v3/ai/chat/attachment/parse/upload}.
     *
     * @param file uploaded file for the request.
     * @return data result containing AI attachment response.
     */
    @PostMapping("/chat/attachment/parse/upload")
    public DataResult<AiAttachmentResponse> parseUploadedAttachment(@RequestParam("file") MultipartFile file) {
        return DataResult.of(chatConverter.attachment2response(aiAttachmentParseService.parseUpload(file)));
    }

    /**
     * Handles parse local attachment for AI chat.
     * <p>
     * Endpoint: {@code POST /api/v3/ai/chat/attachment/parse/local}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing AI attachment response.
     */
    @PostMapping("/chat/attachment/parse/local")
    public DataResult<AiAttachmentResponse> parseLocalAttachment(@RequestBody @Valid ParseLocalAttachmentRequest request) {
        return DataResult.of(chatConverter.attachment2response(aiAttachmentParseService.parseLocal(request)));
    }

    /**
     * Handles model list for AI chat.
     * <p>
     * Endpoint: {@code GET /api/v3/ai/model/list}.
     *
     * @return data result containing AI model catalog item.
     */
    @GetMapping("/model/list")
    public DataResult<List<AiModelCatalogItem>> modelList() {
        return DataResult.of(aiModelConfigService.listPresetModels());
    }

    /**
     * Handles model options for AI chat.
     * <p>
     * Endpoint: {@code GET /api/v3/ai/model/options}.
     *
     * @return data result containing AI model option item.
     */
    @GetMapping("/model/options")
    public DataResult<List<AiModelOptionItem>> modelOptions() {
        return DataResult.of(aiModelConfigService.listModelOptions());
    }

    /**
     * Handles model config list for AI chat.
     * <p>
     * Endpoint: {@code GET /api/v3/ai/model/config/list}.
     *
     * @return data result containing AI model config response.
     */
    @GetMapping("/model/config/list")
    public DataResult<List<AiModelConfigResponse>> modelConfigList() {
        return DataResult.of(aiModelConfigService.listCurrentUserConfigs());
    }

    /**
     * Saves model config.
     * <p>
     * Endpoint: {@code POST /api/v3/ai/model/config/save}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing AI model config response.
     */
    @PostMapping("/model/config/save")
    public DataResult<AiModelConfigResponse> saveModelConfig(@RequestBody @Valid ModelConfigSaveRequest request) {
        return DataResult.of(aiModelConfigService.saveCurrentUserConfig(chatConverter.toModelConfigParam(request)));
    }

    /**
     * Handles test model config for AI chat.
     * <p>
     * Endpoint: {@code POST /api/v3/ai/model/config/test}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing model config test result.
     */
    @PostMapping("/model/config/test")
    public DataResult<ModelConfigTestResponse> testModelConfig(@RequestBody @Valid ModelConfigTestRequest request) {
        return DataResult.of(aiModelConfigService.testModelConfig(chatConverter.toModelConfigParam(request)));
    }

    /**
     * Deletes model config.
     * <p>
     * Endpoint: {@code POST /api/v3/ai/model/config/delete}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/model/config/delete")
    public ActionResult deleteModelConfig(@RequestBody @Valid ModelConfigDeleteRequest request) {
        aiModelConfigService.deleteCurrentUserConfig(request.getId());
        return ActionResult.isSuccess();
    }

    /**
     * Lists sessions.
     * <p>
     * Endpoint: {@code GET /api/v3/ai/chat/history/sessions}.
     *
     * @return list result containing AI chat session response.
     */
    @GetMapping("/chat/history/sessions")
    public ListResult<AiChatSessionResponse> listSessions() {
        Long userId = identityService.currentUserId();
        List<AiChatSession> sessions = aiChatHistoryService.listSessions(userId);
        return ListResult.of(chatConverter.session2response(sessions));
    }

    /**
     * Lists messages.
     * <p>
     * Endpoint: {@code GET /api/v3/ai/chat/history/messages}.
     *
     * @param sessionId identifier used to locate the target resource.
     * @return list result containing AI chat message response.
     */
    @GetMapping("/chat/history/messages")
    public ListResult<AiChatMessageResponse> listMessages(@RequestParam("sessionId") String sessionId) {
        Long userId = identityService.currentUserId();
        List<AiChatMessage> messages = aiChatHistoryService.getMessages(sessionId, userId);
        return ListResult.of(chatConverter.message2response(messages));
    }

    /**
     * Renames a session owned by the current user.
     *
     * @param request session identifier and new title.
     * @return operation result for the request.
     */
    @PostMapping("/chat/history/session/rename")
    public ActionResult renameSession(@RequestBody @Valid AiChatSessionRenameRequest request) {
        Long userId = identityService.currentUserId();
        aiChatHistoryService.renameSession(request.getId(), userId, request.getTitle());
        return ActionResult.isSuccess();
    }

    /**
     * Deletes session.
     * <p>
     * Endpoint: {@code POST /api/v3/ai/chat/history/session/delete}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/chat/history/session/delete")
    public ActionResult deleteSession(@RequestBody @Valid AiChatSessionDeleteRequest request) {
        Long userId = identityService.currentUserId();
        aiChatHistoryService.deleteSession(request.getId(), userId);
        return ActionResult.isSuccess();
    }
}

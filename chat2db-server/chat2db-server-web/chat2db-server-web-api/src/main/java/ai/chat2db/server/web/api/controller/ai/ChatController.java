package ai.chat2db.server.web.api.controller.ai;

import ai.chat2db.server.domain.api.enums.AiSqlSourceEnum;
import ai.chat2db.server.domain.api.model.Config;
import ai.chat2db.server.domain.api.model.DataSource;
import ai.chat2db.server.domain.api.param.ShowCreateTableParam;
import ai.chat2db.server.domain.api.param.TableQueryParam;
import ai.chat2db.server.domain.api.service.ConfigService;
import ai.chat2db.server.domain.api.service.DataSourceService;
import ai.chat2db.server.domain.api.service.TableService;
import ai.chat2db.server.tools.base.enums.WhiteListTypeEnum;
import ai.chat2db.server.tools.base.wrapper.result.DataResult;
import ai.chat2db.server.tools.common.exception.ParamBusinessException;
import ai.chat2db.server.tools.common.util.EasyEnumUtils;
import ai.chat2db.server.web.api.aspect.ConnectionInfoAspect;
import ai.chat2db.server.web.api.controller.ai.azure.client.AzureOpenAIClient;
import ai.chat2db.server.web.api.controller.ai.azure.listener.AzureOpenAIEventSourceListener;
import ai.chat2db.server.web.api.controller.ai.azure.model.AzureChatMessage;
import ai.chat2db.server.web.api.controller.ai.azure.model.AzureChatRole;
import ai.chat2db.server.web.api.controller.ai.baichuan.client.BaichuanAIClient;
import ai.chat2db.server.web.api.controller.ai.baichuan.listener.BaichuanChatAIEventSourceListener;
import ai.chat2db.server.web.api.controller.ai.chat2db.client.Chat2dbAIClient;
import ai.chat2db.server.web.api.controller.ai.chat2db.listener.Chat2dbAIEventSourceListener;
import ai.chat2db.server.web.api.controller.ai.claude.client.ClaudeAIClient;
import ai.chat2db.server.web.api.controller.ai.claude.listener.ClaudeAIEventSourceListener;
import ai.chat2db.server.web.api.controller.ai.claude.model.ClaudeChatCompletionsOptions;
import ai.chat2db.server.web.api.controller.ai.claude.model.ClaudeChatMessage;
import ai.chat2db.server.web.api.controller.ai.config.LocalCache;
import ai.chat2db.server.web.api.controller.ai.converter.ChatConverter;
import ai.chat2db.server.web.api.controller.ai.enums.PromptType;
import ai.chat2db.server.web.api.controller.ai.fastchat.client.FastChatAIClient;
import ai.chat2db.server.web.api.controller.ai.fastchat.embeddings.FastChatEmbeddingResponse;
import ai.chat2db.server.web.api.controller.ai.fastchat.listener.FastChatAIEventSourceListener;
import ai.chat2db.server.web.api.controller.ai.fastchat.model.FastChatMessage;
import ai.chat2db.server.web.api.controller.ai.fastchat.model.FastChatRole;
import ai.chat2db.server.web.api.controller.ai.openai.client.OpenAIClient;
import ai.chat2db.server.web.api.controller.ai.openai.listener.OpenAIEventSourceListener;
import ai.chat2db.server.web.api.controller.ai.request.ChatQueryRequest;
import ai.chat2db.server.web.api.controller.ai.request.ChatRequest;
import ai.chat2db.server.web.api.controller.ai.rest.client.RestAIClient;
import ai.chat2db.server.web.api.controller.ai.rest.listener.RestAIEventSourceListener;
import ai.chat2db.server.web.api.controller.ai.tongyi.client.TongyiChatAIClient;
import ai.chat2db.server.web.api.controller.ai.tongyi.listener.TongyiChatAIEventSourceListener;
import ai.chat2db.server.web.api.controller.ai.wenxin.client.WenxinAIClient;
import ai.chat2db.server.web.api.controller.ai.wenxin.listener.WenxinAIEventSourceListener;
import ai.chat2db.server.web.api.controller.ai.zhipu.client.ZhipuChatAIClient;
import ai.chat2db.server.web.api.controller.ai.zhipu.listener.ZhipuChatAIEventSourceListener;
import ai.chat2db.server.web.api.http.GatewayClientService;
import ai.chat2db.server.web.api.http.model.EsTableSchema;
import ai.chat2db.server.web.api.http.model.TableSchema;
import ai.chat2db.server.web.api.http.request.EsTableSchemaRequest;
import ai.chat2db.server.web.api.http.request.TableSchemaRequest;
import ai.chat2db.server.web.api.http.request.WhiteListRequest;
import ai.chat2db.server.web.api.http.response.EsTableSchemaResponse;
import ai.chat2db.server.web.api.http.response.TableSchemaResponse;
import ai.chat2db.server.web.api.util.ApplicationContextUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Lists;
import com.unfbx.chatgpt.entity.chat.Message;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * description：
 *
 * @author https:www.unfbx.com
 * @date 2023-03-01
 */
@RestController
@ConnectionInfoAspect
@RequestMapping("/api/ai")
@Slf4j
public class ChatController {

    @Autowired
    private TableService tableService;

    @Autowired
    private ChatConverter chatConverter;

    @Autowired
    private DataSourceService dataSourceService;

    @Value("${chatgpt.context.length}")
    private Integer contextLength;

    @Value("${chatgpt.version}")
    private String gptVersion;

    @Resource
    private GatewayClientService gatewayClientService;

    /**
     * chat timeout
     */
    private static final Long CHAT_TIMEOUT = Duration.ofMinutes(50).toMillis();

    /**
     * Maximum number of tokens for prompts
     */
    private Integer MAX_PROMPT_LENGTH = 3850;

    /**
     * token conversion string length
     */
    private Integer TOKEN_CONVERT_CHAR_LENGTH = 4;

    /**
     * Return token size
     */
    private Integer RETURN_TOKEN_LENGTH = 150;


    /**
     * Custom model streaming output interface DEMO
     * <p>
     *     Note: For custom AI that uses its own local streaming output, the interface input and output must be consistent with this sample.
     * </p>
     *
     * @param queryRequest
     * @return
     * @throws IOException
     */
    @PostMapping("/custom/stream/chat")
    @CrossOrigin
    public SseEmitter customChat(@RequestBody ChatRequest queryRequest) throws IOException {
        SseEmitter emitter = new SseEmitter(CHAT_TIMEOUT);

        // Set event handler for SSEEmitter
        emitter.onCompletion(() -> log.info(LocalDateTime.now() + ", on completion"));
        emitter.onTimeout(() -> {
            log.info(LocalDateTime.now() + ", uid# on timeout");
            emitter.complete();
        });

        // Start a new thread to generate SSE events
        new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    emitter.send(SseEmitter.event().name("message").data("Event " + i));
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                emitter.complete();
            }
        }).start();

        return emitter;
    }

    /**
     * Custom model non-streaming output interface DEMO
     * <p>
     *       Note: Use your own local flying flow output to customize the AI. The interface input and output must be consistent with this sample.
     * </p>
     *
     * @param queryRequest
     * @return
     * @throws IOException
     */
    @PostMapping("/custom/non/stream/chat")
    @CrossOrigin
    public String customNonStreamChat(@RequestBody ChatRequest queryRequest) throws IOException {
        String data = "The custom AI sample interface is connected successfully! ! ! !";
        return data;
    }

    /**
     * SQL conversion model
     *
     * @param queryRequest
     * @param headers
     * @return
     * @throws IOException
     */
    @GetMapping("/chat")
    @CrossOrigin
    public SseEmitter completions(ChatQueryRequest queryRequest, @RequestHeader Map<String, String> headers)
        throws IOException {
        //The default timeout is 30 seconds. If set to 0L, it will never timeout.
        SseEmitter sseEmitter = new SseEmitter(CHAT_TIMEOUT);
        String uid = headers.get("uid");
        if (StrUtil.isBlank(uid)) {
            throw new ParamBusinessException("uid");
        }

        //Prompt message cannot be empty
        if (StringUtils.isBlank(queryRequest.getMessage())) {
            throw new ParamBusinessException("message");
        }

        return distributeAISql(queryRequest, sseEmitter, uid);
    }

    /**
     * distribute with different AI
     *
     * @return
     */
    public SseEmitter distributeAISql(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid) throws IOException {
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config config = configService.find(RestAIClient.AI_SQL_SOURCE).getData();
        String aiSqlSource = AiSqlSourceEnum.CHAT2DBAI.getCode();
        if (Objects.nonNull(config)) {
            aiSqlSource = config.getContent();
        }
        AiSqlSourceEnum aiSqlSourceEnum = AiSqlSourceEnum.getByName(aiSqlSource);
        if (Objects.isNull(aiSqlSourceEnum)) {
            aiSqlSourceEnum = AiSqlSourceEnum.OPENAI;
        }
        uid = aiSqlSourceEnum.getCode() + uid;
        switch (Objects.requireNonNull(aiSqlSourceEnum)) {
            case OPENAI :
                return chatWithOpenAi(queryRequest, sseEmitter, uid);
            case CHAT2DBAI:
                return chatWithChat2dbAi(queryRequest, sseEmitter, uid);
            case RESTAI :
                return chatWithRestAi(queryRequest, sseEmitter, uid);
            case FASTCHATAI:
                return chatWithFastChatAi(queryRequest, sseEmitter, uid);
            case AZUREAI :
                return chatWithAzureAi(queryRequest, sseEmitter, uid);
            case CLAUDEAI:
                return chatWithClaudeAi(queryRequest, sseEmitter, uid);
            case WENXINAI:
                return chatWithWenxinAi(queryRequest, sseEmitter, uid);
            case BAICHUANAI:
                return chatWithBaichuanAi(queryRequest, sseEmitter, uid);
            case TONGYIQIANWENAI:
                return chatWithTongyiChatAi(queryRequest, sseEmitter, uid);
            case ZHIPUAI:
                return chatWithZhipuChatAi(queryRequest, sseEmitter, uid);
        }
        return chatWithOpenAi(queryRequest, sseEmitter, uid);
    }

    /**
     * Chat using a custom AI interface
     *
     * @param prompt
     * @param sseEmitter
     * @return
     */
    private SseEmitter chatWithRestAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid) throws IOException {
        String prompt = buildPrompt(queryRequest);
        List<FastChatMessage> messages = getFastChatMessage(uid, prompt);

        buildSseEmitter(sseEmitter, uid);

        RestAIEventSourceListener restAIEventSourceListener = new RestAIEventSourceListener(sseEmitter);
        RestAIClient.getInstance().streamCompletions(messages, restAIEventSourceListener);
        LocalCache.CACHE.put(uid, JSONUtil.toJsonStr(messages), LocalCache.TIMEOUT);
        return sseEmitter;
    }

    /**
     * Using the OPENAI SQL interface
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithOpenAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid)
        throws IOException {
        String prompt = buildPrompt(queryRequest);
        if (prompt.length() / TOKEN_CONVERT_CHAR_LENGTH > MAX_PROMPT_LENGTH) {
            log.error("The prompt exceeds the maximum length: {}, input length: {}, please re-enter", MAX_PROMPT_LENGTH,
                prompt.length() / TOKEN_CONVERT_CHAR_LENGTH);
            throw new ParamBusinessException();
        }

        List<Message> messages = new ArrayList<>();
        prompt = prompt.replaceAll("#", "");
        log.info(prompt);
        Message currentMessage = Message.builder().content(prompt).role(Message.Role.USER).build();
        messages.add(currentMessage);
        buildSseEmitter(sseEmitter, uid);

        OpenAIEventSourceListener openAIEventSourceListener = new OpenAIEventSourceListener(sseEmitter);
        OpenAIClient.getInstance().streamChatCompletion(messages, openAIEventSourceListener);
        LocalCache.CACHE.put(uid, JSONUtil.toJsonStr(messages), LocalCache.TIMEOUT);
        return sseEmitter;
    }

    /**
     * Using the OPENAI SQL interface
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithChat2dbAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid)
        throws IOException {
        String prompt = buildPrompt(queryRequest);
        if (prompt.length() / TOKEN_CONVERT_CHAR_LENGTH > MAX_PROMPT_LENGTH) {
            log.error("exceed max token length:{}，input length:{}", MAX_PROMPT_LENGTH,
                prompt.length() / TOKEN_CONVERT_CHAR_LENGTH);
            throw new ParamBusinessException();
        }

        prompt = prompt.replaceAll("#", "");
        log.info(prompt);
        Message currentMessage = Message.builder().content(prompt).role(Message.Role.USER).build();
        List<Message> messages = new ArrayList<>();
        messages.add(currentMessage);
        buildSseEmitter(sseEmitter, uid);

        Chat2dbAIEventSourceListener openAIEventSourceListener = new Chat2dbAIEventSourceListener(sseEmitter);
        Chat2dbAIClient.getInstance().streamCompletions(messages, openAIEventSourceListener);
        LocalCache.CACHE.put(uid, JSONUtil.toJsonStr(messages), LocalCache.TIMEOUT);
        return sseEmitter;
    }

    /**
     * chat with azure openai
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithAzureAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid) throws IOException {
        String prompt = buildPrompt(queryRequest);
        if (prompt.length() / TOKEN_CONVERT_CHAR_LENGTH > MAX_PROMPT_LENGTH) {
            log.error("The prompt exceeds the maximum length: {}, input length: {}, please re-enter", MAX_PROMPT_LENGTH,
                    prompt.length() / TOKEN_CONVERT_CHAR_LENGTH);
            throw new ParamBusinessException();
        }
        List<AzureChatMessage> messages = (List<AzureChatMessage>)LocalCache.CACHE.get(uid);
        if (CollectionUtils.isNotEmpty(messages)) {
            if (messages.size() >= contextLength) {
                messages = messages.subList(1, contextLength);
            }
        } else {
            messages = Lists.newArrayList();
        }
        AzureChatMessage currentMessage = new AzureChatMessage(AzureChatRole.USER).setContent(prompt);
        messages.add(currentMessage);

        buildSseEmitter(sseEmitter, uid);

        AzureOpenAIEventSourceListener sourceListener = new AzureOpenAIEventSourceListener(sseEmitter);
        AzureOpenAIClient.getInstance().streamCompletions(messages, sourceListener);
        LocalCache.CACHE.put(uid, messages, LocalCache.TIMEOUT);
        return sseEmitter;
    }

    /**
     * chat with fast chat openai
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithFastChatAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid) throws IOException {
        String prompt = buildPrompt(queryRequest);
        List<FastChatMessage> messages = getFastChatMessage(uid, prompt);

        buildSseEmitter(sseEmitter, uid);

        FastChatAIEventSourceListener sourceListener = new FastChatAIEventSourceListener(sseEmitter);
        FastChatAIClient.getInstance().streamCompletions(messages, sourceListener);
        LocalCache.CACHE.put(uid, messages, LocalCache.TIMEOUT);
        return sseEmitter;
    }

    /**
     * chat with zhipu chat openai
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithZhipuChatAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid) throws IOException {
        String prompt = buildPrompt(queryRequest);
        List<FastChatMessage> messages = getFastChatMessage(uid, prompt);

        buildSseEmitter(sseEmitter, uid);

        ZhipuChatAIEventSourceListener sourceListener = new ZhipuChatAIEventSourceListener(sseEmitter);
        ZhipuChatAIClient.getInstance().streamCompletions(messages, sourceListener);
        LocalCache.CACHE.put(uid, messages, LocalCache.TIMEOUT);
        return sseEmitter;
    }

    /**
     * chat with tongyi chat openai
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithTongyiChatAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid) throws IOException {
        String prompt = buildPrompt(queryRequest);
        List<FastChatMessage> messages = getFastChatMessage(uid, prompt);

        buildSseEmitter(sseEmitter, uid);

        TongyiChatAIEventSourceListener sourceListener = new TongyiChatAIEventSourceListener(sseEmitter);
        TongyiChatAIClient.getInstance().streamCompletions(messages, sourceListener);
        LocalCache.CACHE.put(uid, messages, LocalCache.TIMEOUT);
        return sseEmitter;
    }

    /**
     * chat with baichuan chat openai
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithBaichuanAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid) throws IOException {
        String prompt = buildPrompt(queryRequest);
        List<FastChatMessage> messages = getFastChatMessage(uid, prompt);

        buildSseEmitter(sseEmitter, uid);

        BaichuanChatAIEventSourceListener sourceListener = new BaichuanChatAIEventSourceListener(sseEmitter);
        BaichuanAIClient.getInstance().streamCompletions(messages, sourceListener);
        LocalCache.CACHE.put(uid, messages, LocalCache.TIMEOUT);
        return sseEmitter;
    }

    /**
     * get fast chat message
     *
     * @param uid
     * @param prompt
     * @return
     */
    private List<FastChatMessage> getFastChatMessage(String uid, String prompt) {
        List<FastChatMessage> messages = (List<FastChatMessage>)LocalCache.CACHE.get(uid);
        if (CollectionUtils.isNotEmpty(messages)) {
            if (messages.size() >= contextLength) {
                messages = messages.subList(1, contextLength);
            }
        } else {
            messages = Lists.newArrayList();
        }
        FastChatMessage currentMessage = new FastChatMessage(FastChatRole.USER).setContent(prompt);
        messages.add(currentMessage);
        return messages;
    }

    /**
     * chat with wenxin chat openai
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithWenxinAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid) throws IOException {
        String prompt = buildPrompt(queryRequest);
        List<FastChatMessage> messages = getFastChatMessage(uid, prompt);
        if (messages.size() >= 2 && messages.size() % 2 == 0) {
            messages.remove(messages.size() - 1);
        }

        buildSseEmitter(sseEmitter, uid);

        WenxinAIEventSourceListener sourceListener = new WenxinAIEventSourceListener(sseEmitter);
        WenxinAIClient.getInstance().streamCompletions(messages, sourceListener);
        LocalCache.CACHE.put(uid, messages, LocalCache.TIMEOUT);
        return sseEmitter;
    }


    /**
     * chat with claude ai
     *
     * @param queryRequest
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter chatWithClaudeAi(ChatQueryRequest queryRequest, SseEmitter sseEmitter, String uid) throws IOException {
        String prompt = buildPrompt(queryRequest);
        ClaudeChatMessage claudeChatMessage = new ClaudeChatMessage();
        claudeChatMessage.setText(prompt);
        ClaudeChatCompletionsOptions chatCompletionsOptions = new ClaudeChatCompletionsOptions();
        chatCompletionsOptions.setPrompt(prompt);
        claudeChatMessage.setCompletion(chatCompletionsOptions);

        buildSseEmitter(sseEmitter, uid);

        ClaudeAIEventSourceListener sourceListener = new ClaudeAIEventSourceListener(sseEmitter);
        ClaudeAIClient.getInstance().streamCompletions(claudeChatMessage, sourceListener);
        return sseEmitter;
    }

    /**
     * construct sseEmitter
     *
     * @param sseEmitter
     * @param uid
     * @return
     * @throws IOException
     */
    private SseEmitter buildSseEmitter(SseEmitter sseEmitter, String uid) throws IOException {
        sseEmitter.send(SseEmitter.event().id(uid).name("connect successfully！！！！").data(LocalDateTime.now()).reconnectTime(3000));
        sseEmitter.onCompletion(() -> {
            log.info(LocalDateTime.now() + ", uid#" + uid + ", on completion");
        });
        sseEmitter.onTimeout(
            () -> log.info(LocalDateTime.now() + ", uid#" + uid + ", on timeout#" + sseEmitter.getTimeout()));
        sseEmitter.onError(
            throwable -> {
                try {
                    log.info(LocalDateTime.now() + ", uid#" + "765431" + ", on error#" + throwable.toString());
                    sseEmitter.send(SseEmitter.event().id("765431").name("An exception occurs!").data(throwable.getMessage())
                        .reconnectTime(3000));
                } catch (IOException e) {
                    log.error("An exception occurs!{}", e.getMessage(), e);
                }
            }
        );
        return sseEmitter;
    }

    /**
     * Build schema parameters
     *
     * @param tableQueryParam
     * @param tableNames
     * @return
     */
    private String buildTableColumn(TableQueryParam tableQueryParam,
        List<String> tableNames) {
        if (CollectionUtils.isEmpty(tableNames)) {
            return "";
        }
        List<String> schemaContent = Lists.newArrayList();
        try {
             schemaContent = tableNames.stream().map(tableName -> {
                tableQueryParam.setTableName(tableName);
                return queryTableDdl(tableName, tableQueryParam);
            }).collect(Collectors.toList());
        } catch (Exception exception) {
            log.error("query table error, do nothing");
        }

        return JSON.toJSONString(schemaContent);
    }

    /**
     * query table schema
     *
     * @param tableName
     * @param request
     * @return
     */
    private String queryTableDdl(String tableName, TableQueryParam request) {
        ShowCreateTableParam param = new ShowCreateTableParam();
        param.setTableName(tableName);
        param.setDataSourceId(request.getDataSourceId());
        param.setDatabaseName(request.getDatabaseName());
        param.setSchemaName(request.getSchemaName());
        DataResult<String> tableSchema = tableService.showCreateTable(param);
        return tableSchema.getData();
    }

    /**
     * build prompt
     *
     * @param queryRequest
     * @return
     */
    private String buildPrompt(ChatQueryRequest queryRequest) {
        if (PromptType.TEXT_GENERATION.getCode().equals(queryRequest.getPromptType())) {
            return queryRequest.getMessage();
        }

        // Query schema information
        String dataSourceType = queryDatabaseType(queryRequest);
        String properties = "";
        if (CollectionUtils.isNotEmpty(queryRequest.getTableNames())) {
            TableQueryParam queryParam = chatConverter.chat2tableQuery(queryRequest);
            properties = buildTableColumn(queryParam, queryRequest.getTableNames());
        } else {
            properties = mappingDatabaseSchema(queryRequest);
        }
        String prompt = queryRequest.getMessage();
        String promptType = StringUtils.isBlank(queryRequest.getPromptType()) ? PromptType.NL_2_SQL.getCode()
            : queryRequest.getPromptType();
        PromptType pType = EasyEnumUtils.getEnum(PromptType.class, promptType);
        String ext = StringUtils.isNotBlank(queryRequest.getExt()) ? queryRequest.getExt() : "";
        String schemaProperty = StringUtils.isNotEmpty(properties) ? String.format(
            "### Please follow the below table properties and SQL input%s. %s\n#\n### %s SQL tables, with their properties:\n#\n# "
                + "%s\n#\n#\n### SQL input: %s", pType.getDescription(), ext, dataSourceType,
            properties, prompt) : String.format("### Please follow the below SQL input%s. %s\n#\n### SQL input: %s",
            pType.getDescription(), ext, prompt);
        switch (pType) {
            case SQL_2_SQL:
                schemaProperty = StringUtils.isNotBlank(queryRequest.getDestSqlType()) ? String.format(
                    "%s\n#\n### Target SQL type: %s", schemaProperty, queryRequest.getDestSqlType()) : String.format(
                    "%s\n#\n### Target SQL type: %s", schemaProperty, dataSourceType);
            default:
                break;
        }
        String cleanedInput = schemaProperty.replaceAll("[\r\t]", "");
        return cleanedInput;
    }

    /**
     * query chat2db apikey
     *
     * @return
     */
    public String getApiKey() {
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config config = configService.find(RestAIClient.AI_SQL_SOURCE).getData();
        String aiSqlSource = AiSqlSourceEnum.CHAT2DBAI.getCode();
        // only sync for chat2db ai
        if (Objects.isNull(config) || !aiSqlSource.equals(config.getContent())) {
            return null;
        }
        Config keyConfig = configService.find(Chat2dbAIClient.CHAT2DB_OPENAI_KEY).getData();
        if (Objects.isNull(keyConfig) || StringUtils.isBlank(keyConfig.getContent())) {
            return null;
        }
        return keyConfig.getContent();
    }

    /**
     * query database type
     *
     * @param queryRequest
     * @return
     */
    public String queryDatabaseType(ChatQueryRequest queryRequest) {
        // Query schema information
        DataResult<DataSource> dataResult = dataSourceService.queryById(queryRequest.getDataSourceId());
        String dataSourceType = dataResult.getData().getType();
        if (StringUtils.isBlank(dataSourceType)) {
            dataSourceType = "MYSQL";
        }
        return dataSourceType;
    }

    public String mappingDatabaseSchema(ChatQueryRequest queryRequest) {
        String properties = "";
        String apiKey = getApiKey();
        if (StringUtils.isNotBlank(apiKey)) {
            boolean res = gatewayClientService.checkInWhite(new WhiteListRequest(apiKey, WhiteListTypeEnum.VECTOR.getCode())).getData();
            if (res) {
//                properties = queryDatabaseSchema(queryRequest) + querySchemaByEs(queryRequest);
                properties = queryDatabaseSchema(queryRequest);
            }
        }
        return properties;
    }

    /**
     * query database schema
     *
     * @param queryRequest
     * @return
     * @throws IOException
     */
    public String queryDatabaseSchema(ChatQueryRequest queryRequest) {
        // request embedding
        FastChatEmbeddingResponse response = distributeAIEmbedding(queryRequest.getMessage());
        List<List<BigDecimal>> contentVector = new ArrayList<>();
        if (Objects.isNull(response) || CollectionUtils.isEmpty(response.getData())) {
            return "";
        }
        contentVector.add(response.getData().get(0).getEmbedding());

        // search embedding
        TableSchemaRequest tableSchemaRequest = new TableSchemaRequest();
        tableSchemaRequest.setSchemaVector(contentVector);
        tableSchemaRequest.setDataSourceId(queryRequest.getDataSourceId());
        tableSchemaRequest.setDatabaseName(queryRequest.getDatabaseName());
        tableSchemaRequest.setDataSourceSchema(queryRequest.getSchemaName());
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config keyConfig = configService.find(Chat2dbAIClient.CHAT2DB_OPENAI_KEY).getData();
        if (Objects.isNull(keyConfig) || StringUtils.isBlank(keyConfig.getContent())) {
            return "";
        }
        tableSchemaRequest.setApiKey(keyConfig.getContent());
        try {
            DataResult<TableSchemaResponse> result = gatewayClientService.schemaVectorSearch(tableSchemaRequest);
            List<String> schemas = Lists.newArrayList();
            if (Objects.nonNull(result.getData()) && CollectionUtils.isNotEmpty(result.getData().getTableSchemas())) {
                for(TableSchema data: result.getData().getTableSchemas()){
                    schemas.add(data.getTableSchema());
                }
            }
            if (CollectionUtils.isEmpty(schemas)) {
                return "";
            }
            String res = JSON.toJSONString(schemas);
            log.info("search vector result:{}", res);
            return res;
        } catch (Exception exception) {
            log.error("query table error, do nothing");
            return "";
        }
    }

    /**
     * query database schema
     *
     * @param queryRequest
     * @return
     * @throws IOException
     */
    public String querySchemaByEs(ChatQueryRequest queryRequest) {
        // search embedding
        EsTableSchemaRequest tableSchemaRequest = new EsTableSchemaRequest();
        tableSchemaRequest.setSearchKey(queryRequest.getMessage());
        tableSchemaRequest.setDataSourceId(queryRequest.getDataSourceId());
        tableSchemaRequest.setDatabaseName(queryRequest.getDatabaseName());
        tableSchemaRequest.setSchemaName(queryRequest.getSchemaName());
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config keyConfig = configService.find(Chat2dbAIClient.CHAT2DB_OPENAI_KEY).getData();
        if (Objects.isNull(keyConfig) || StringUtils.isBlank(keyConfig.getContent())) {
            return "";
        }
        tableSchemaRequest.setApiKey(keyConfig.getContent());
        try {
            DataResult<EsTableSchemaResponse> result = gatewayClientService.schemaEsSearch(tableSchemaRequest);
            List<String> schemas = Lists.newArrayList();
            if (Objects.nonNull(result.getData()) && CollectionUtils.isNotEmpty(result.getData().getTableSchemas())) {
                for(EsTableSchema data: result.getData().getTableSchemas()){
                    schemas.add(data.getTableSchemaContent());
                }
            }
            if (CollectionUtils.isEmpty(schemas)) {
                return "";
            }
            String res = JSON.toJSONString(schemas);
            log.info("search es result:{}", res);
            return res;
        } catch (Exception exception) {
            log.error("query es table error, do nothing");
            return "";
        }
    }

    /**
     * distribute embedding with different AI
     *
     * @return
     */
    public FastChatEmbeddingResponse distributeAIEmbedding(String input) {
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config config = configService.find(RestAIClient.AI_SQL_SOURCE).getData();
        String aiSqlSource = config.getContent();
        if (Objects.isNull(aiSqlSource)) {
            return null;
        }
        AiSqlSourceEnum aiSqlSourceEnum = AiSqlSourceEnum.getByName(aiSqlSource);
        switch (Objects.requireNonNull(aiSqlSourceEnum)) {
            case CHAT2DBAI:
                return embeddingWithChat2dbAi(input);
            case FASTCHATAI:
                return embeddingWithFastChatAi(input);
        }
        return null;
    }

    /**
     * embedding with fast chat openai
     *
     * @param input
     * @return
     * @throws IOException
     */
    private FastChatEmbeddingResponse embeddingWithFastChatAi(String input) {
        FastChatEmbeddingResponse response = FastChatAIClient.getInstance().embeddings(input);
        return response;
    }

    /**
     * embedding with open ai
     *
     * @param input
     * @return
     */
    private FastChatEmbeddingResponse embeddingWithChat2dbAi(String input) {
        FastChatEmbeddingResponse embeddings = Chat2dbAIClient.getInstance().embeddings(input);
        return embeddings;
    }

}

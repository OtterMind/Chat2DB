
package ai.chat2db.server.web.api.controller.config;

import java.util.Objects;

import ai.chat2db.server.domain.api.enums.AiSqlSourceEnum;
import ai.chat2db.server.domain.api.model.AIConfig;
import ai.chat2db.server.domain.api.model.Config;
import ai.chat2db.server.domain.api.param.SystemConfigParam;
import ai.chat2db.server.domain.api.service.ConfigService;
import ai.chat2db.server.domain.core.util.PermissionUtils;
import ai.chat2db.server.tools.base.wrapper.result.ActionResult;
import ai.chat2db.server.tools.base.wrapper.result.DataResult;
import ai.chat2db.server.web.api.aspect.ConnectionInfoAspect;
import ai.chat2db.server.web.api.controller.ai.azure.client.AzureOpenAIClient;
import ai.chat2db.server.web.api.controller.ai.baichuan.client.BaichuanAIClient;
import ai.chat2db.server.web.api.controller.ai.chat2db.client.Chat2dbAIClient;
import ai.chat2db.server.web.api.controller.ai.fastchat.client.FastChatAIClient;
import ai.chat2db.server.web.api.controller.ai.rest.client.RestAIClient;
import ai.chat2db.server.web.api.controller.ai.tongyi.client.TongyiChatAIClient;
import ai.chat2db.server.web.api.controller.ai.wenxin.client.WenxinAIClient;
import ai.chat2db.server.web.api.controller.ai.zhipu.client.ZhipuChatAIClient;
import ai.chat2db.server.web.api.controller.config.request.AIConfigCreateRequest;
import ai.chat2db.server.web.api.controller.config.request.SystemConfigRequest;
import ai.chat2db.server.web.api.controller.ai.openai.client.OpenAIClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author jipengfei
 * @version : ConfigController.java
 */
@ConnectionInfoAspect
@RequestMapping("/api/config")
@RestController
public class ConfigController {

    @Autowired
    private ConfigService configService;

    @PostMapping("/system_config")
    public ActionResult systemConfig(@RequestBody SystemConfigRequest request) {
        SystemConfigParam param = SystemConfigParam.builder().code(request.getCode()).content(request.getContent())
            .build();
        configService.createOrUpdate(param);
        if (OpenAIClient.OPENAI_KEY.equals(request.getCode())) {
            OpenAIClient.refresh();
        }
        return ActionResult.isSuccess();
    }

    /**
     * Save ChatGPT related configurations
     *
     * @param request
     * @return
     */
    @PostMapping("/system_config/ai")
    public ActionResult addChatGptSystemConfig(@RequestBody AIConfigCreateRequest request) {
        PermissionUtils.checkDeskTopOrAdmin();

        String sqlSource = request.getAiSqlSource();
        AiSqlSourceEnum aiSqlSourceEnum = AiSqlSourceEnum.getByName(sqlSource);
        if (Objects.isNull(aiSqlSourceEnum)) {
            sqlSource = AiSqlSourceEnum.CHAT2DBAI.getCode();
            aiSqlSourceEnum = AiSqlSourceEnum.CHAT2DBAI;
        }
        SystemConfigParam param = SystemConfigParam.builder().code(RestAIClient.AI_SQL_SOURCE).content(sqlSource)
            .build();
        configService.createOrUpdate(param);

        switch (Objects.requireNonNull(aiSqlSourceEnum)) {
            case OPENAI:
                saveOpenAIConfig(request);
                break;
            case CHAT2DBAI:
                saveChat2dbAIConfig(request);
                break;
            case RESTAI:
                saveRestAIConfig(request);
                break;
            case AZUREAI:
                saveAzureAIConfig(request);
                break;
            case FASTCHATAI:
                saveFastChatAIConfig(request);
                break;
            case TONGYIQIANWENAI:
                saveTongyiChatAIConfig(request);
                break;
            case WENXINAI:
                saveWenxinAIConfig(request);
                break;
            case BAICHUANAI:
                saveBaichuanAIConfig(request);
                break;
            case ZHIPUAI:
                saveZhipuChatAIConfig(request);
                break;
        }
        return ActionResult.isSuccess();
    }

    /**
     * save chat2db ai config
     *
     * @param request
     */
    private void saveChat2dbAIConfig(AIConfigCreateRequest request) {
        SystemConfigParam param = SystemConfigParam.builder().code(Chat2dbAIClient.CHAT2DB_OPENAI_KEY).content(
            request.getApiKey()).build();
        configService.createOrUpdate(param);
        SystemConfigParam hostParam = SystemConfigParam.builder().code(Chat2dbAIClient.CHAT2DB_OPENAI_HOST).content(
            request.getApiHost()).build();
        configService.createOrUpdate(hostParam);
        SystemConfigParam modelParam = SystemConfigParam.builder().code(Chat2dbAIClient.CHAT2DB_OPENAI_MODEL).content(
                request.getModel()).build();
        configService.createOrUpdate(modelParam);
        Chat2dbAIClient.refresh();
    }

    /**
     * save open ai config
     *
     * @param request
     */
    private void saveOpenAIConfig(AIConfigCreateRequest request) {
        SystemConfigParam param = SystemConfigParam.builder().code(OpenAIClient.OPENAI_KEY).content(
            request.getApiKey()).build();
        configService.createOrUpdate(param);
        SystemConfigParam hostParam = SystemConfigParam.builder().code(OpenAIClient.OPENAI_HOST).content(
            request.getApiHost()).build();
        configService.createOrUpdate(hostParam);
        SystemConfigParam httpProxyHostParam = SystemConfigParam.builder().code(OpenAIClient.PROXY_HOST).content(
            request.getHttpProxyHost()).build();
        configService.createOrUpdate(httpProxyHostParam);
        SystemConfigParam httpProxyPortParam = SystemConfigParam.builder().code(OpenAIClient.PROXY_PORT).content(
            request.getHttpProxyPort()).build();
        configService.createOrUpdate(httpProxyPortParam);
        OpenAIClient.refresh();
    }

    /**
     * save rest ai config
     *
     * @param request
     */
    private void saveRestAIConfig(AIConfigCreateRequest request) {
        SystemConfigParam param = SystemConfigParam.builder().code(RestAIClient.REST_AI_API_KEY).content(
                request.getApiKey()).build();
        configService.createOrUpdate(param);
        SystemConfigParam restParam = SystemConfigParam.builder().code(RestAIClient.REST_AI_URL).content(
            request.getApiHost()).build();
        configService.createOrUpdate(restParam);
        SystemConfigParam modelParam = SystemConfigParam.builder().code(RestAIClient.REST_AI_MODEL)
                .content(request.getModel()).build();
        configService.createOrUpdate(modelParam);
        RestAIClient.refresh();
    }

    /**
     * save azure config
     *
     * @param request
     */
    private void saveAzureAIConfig(AIConfigCreateRequest request) {
        SystemConfigParam apikeyParam = SystemConfigParam.builder().code(AzureOpenAIClient.AZURE_CHATGPT_API_KEY)
            .content(
                request.getApiKey()).build();
        configService.createOrUpdate(apikeyParam);
        SystemConfigParam endpointParam = SystemConfigParam.builder().code(AzureOpenAIClient.AZURE_CHATGPT_ENDPOINT)
            .content(
                request.getApiHost()).build();
        configService.createOrUpdate(endpointParam);
        SystemConfigParam modelParam = SystemConfigParam.builder().code(AzureOpenAIClient.AZURE_CHATGPT_DEPLOYMENT_ID)
            .content(
                request.getModel()).build();
        configService.createOrUpdate(modelParam);
        AzureOpenAIClient.refresh();
    }

    /**
     * save common fast chat ai config
     *
     * @param request
     */
    private void saveFastChatAIConfig(AIConfigCreateRequest request) {
        SystemConfigParam apikeyParam = SystemConfigParam.builder().code(FastChatAIClient.FASTCHAT_API_KEY)
                .content(request.getApiKey()).build();
        configService.createOrUpdate(apikeyParam);
        SystemConfigParam apiHostParam = SystemConfigParam.builder().code(FastChatAIClient.FASTCHAT_HOST)
                .content(request.getApiHost()).build();
        configService.createOrUpdate(apiHostParam);
        SystemConfigParam modelParam = SystemConfigParam.builder().code(FastChatAIClient.FASTCHAT_MODEL)
                .content(request.getModel()).build();
        configService.createOrUpdate(modelParam);
        FastChatAIClient.refresh();
    }

    /**
     * save common zhipu chat ai config
     *
     * @param request
     */
    private void saveZhipuChatAIConfig(AIConfigCreateRequest request) {
        SystemConfigParam apikeyParam = SystemConfigParam.builder().code(ZhipuChatAIClient.ZHIPU_API_KEY)
                .content(request.getApiKey()).build();
        configService.createOrUpdate(apikeyParam);
        SystemConfigParam apiHostParam = SystemConfigParam.builder().code(ZhipuChatAIClient.ZHIPU_HOST)
                .content(request.getApiHost()).build();
        configService.createOrUpdate(apiHostParam);
        SystemConfigParam modelParam = SystemConfigParam.builder().code(ZhipuChatAIClient.ZHIPU_MODEL)
                .content(request.getModel()).build();
        configService.createOrUpdate(modelParam);
        ZhipuChatAIClient.refresh();
    }

    /**
     * save common tongyi chat ai config
     *
     * @param request
     */
    private void saveTongyiChatAIConfig(AIConfigCreateRequest request) {
        SystemConfigParam apikeyParam = SystemConfigParam.builder().code(TongyiChatAIClient.TONGYI_API_KEY)
                .content(request.getApiKey()).build();
        configService.createOrUpdate(apikeyParam);
        SystemConfigParam apiHostParam = SystemConfigParam.builder().code(TongyiChatAIClient.TONGYI_HOST)
                .content(request.getApiHost()).build();
        configService.createOrUpdate(apiHostParam);
        SystemConfigParam modelParam = SystemConfigParam.builder().code(TongyiChatAIClient.TONGYI_MODEL)
                .content(request.getModel()).build();
        configService.createOrUpdate(modelParam);
        TongyiChatAIClient.refresh();
    }

    /**
     * save common wenxin chat ai config
     *
     * @param request
     */
    private void saveWenxinAIConfig(AIConfigCreateRequest request) {
        SystemConfigParam apikeyParam = SystemConfigParam.builder().code(WenxinAIClient.WENXIN_ACCESS_TOKEN)
                .content(request.getApiKey()).build();
        configService.createOrUpdate(apikeyParam);
        SystemConfigParam apiHostParam = SystemConfigParam.builder().code(WenxinAIClient.WENXIN_HOST)
                .content(request.getApiHost()).build();
        configService.createOrUpdate(apiHostParam);
        WenxinAIClient.refresh();
    }

    /**
     * save common fast chat ai config
     *
     * @param request
     */
    private void saveBaichuanAIConfig(AIConfigCreateRequest request) {
        SystemConfigParam apikeyParam = SystemConfigParam.builder().code(BaichuanAIClient.BAICHUAN_API_KEY)
                .content(request.getApiKey()).build();
        configService.createOrUpdate(apikeyParam);
        SystemConfigParam secretKeyParam = SystemConfigParam.builder().code(BaichuanAIClient.BAICHUAN_SECRET_KEY)
                .content(request.getSecretKey()).build();
        configService.createOrUpdate(secretKeyParam);
        SystemConfigParam apiHostParam = SystemConfigParam.builder().code(BaichuanAIClient.BAICHUAN_HOST)
                .content(request.getApiHost()).build();
        configService.createOrUpdate(apiHostParam);
        SystemConfigParam modelParam = SystemConfigParam.builder().code(BaichuanAIClient.BAICHUAN_MODEL)
                .content(request.getModel()).build();
        configService.createOrUpdate(modelParam);
        BaichuanAIClient.refresh();
    }

    @GetMapping("/system_config/{code}")
    public DataResult<Config> getSystemConfig(@PathVariable("code") String code) {
        DataResult<Config> result = configService.find(code);
        return DataResult.of(result.getData());
    }

    /**
     * ai config info
     *
     * @return
     */
    @GetMapping("/system_config/ai")
    public DataResult<AIConfig> getChatAiSystemConfig(String aiSqlSource) {
        DataResult<Config> dbSqlSource = configService.find(RestAIClient.AI_SQL_SOURCE);
        if (StringUtils.isBlank(aiSqlSource)) {
            if (Objects.nonNull(dbSqlSource.getData())) {
                aiSqlSource = dbSqlSource.getData().getContent();
            }
        }
        AIConfig config = new AIConfig();
        AiSqlSourceEnum aiSqlSourceEnum = AiSqlSourceEnum.getByName(aiSqlSource);
        if (Objects.isNull(aiSqlSourceEnum)) {
            aiSqlSource = AiSqlSourceEnum.CHAT2DBAI.getCode();
            config.setAiSqlSource(aiSqlSource);
            return DataResult.of(config);
        }
        config.setAiSqlSource(aiSqlSource);
        switch (Objects.requireNonNull(aiSqlSourceEnum)) {
            case OPENAI:
                DataResult<Config> apiKey = configService.find(OpenAIClient.OPENAI_KEY);
                DataResult<Config> apiHost = configService.find(OpenAIClient.OPENAI_HOST);
                DataResult<Config> httpProxyHost = configService.find(OpenAIClient.PROXY_HOST);
                DataResult<Config> httpProxyPort = configService.find(OpenAIClient.PROXY_PORT);
                config.setApiKey(Objects.nonNull(apiKey.getData()) ? apiKey.getData().getContent() : "");
                config.setApiHost(Objects.nonNull(apiHost.getData()) ? apiHost.getData().getContent() : "");
                config.setHttpProxyHost(
                    Objects.nonNull(httpProxyHost.getData()) ? httpProxyHost.getData().getContent() : "");
                config.setHttpProxyPort(
                    Objects.nonNull(httpProxyPort.getData()) ? httpProxyPort.getData().getContent() : "");
                break;
            case CHAT2DBAI:
                DataResult<Config> chat2dbApiKey = configService.find(Chat2dbAIClient.CHAT2DB_OPENAI_KEY);
                DataResult<Config> chat2dbApiHost = configService.find(Chat2dbAIClient.CHAT2DB_OPENAI_HOST);
                DataResult<Config> chat2dbModel = configService.find(Chat2dbAIClient.CHAT2DB_OPENAI_MODEL);
                config.setApiKey(Objects.nonNull(chat2dbApiKey.getData()) ? chat2dbApiKey.getData().getContent() : "");
                config.setApiHost(
                    Objects.nonNull(chat2dbApiHost.getData()) ? chat2dbApiHost.getData().getContent() : "");
                config.setModel(Objects.nonNull(chat2dbModel.getData()) ? chat2dbModel.getData().getContent() : "");
                break;
            case AZUREAI:
                DataResult<Config> azureApiKey = configService.find(AzureOpenAIClient.AZURE_CHATGPT_API_KEY);
                DataResult<Config> azureEndpoint = configService.find(AzureOpenAIClient.AZURE_CHATGPT_ENDPOINT);
                DataResult<Config> azureDeployId = configService.find(AzureOpenAIClient.AZURE_CHATGPT_DEPLOYMENT_ID);
                config.setApiKey(Objects.nonNull(azureApiKey.getData()) ? azureApiKey.getData().getContent() : "");
                config.setApiHost(Objects.nonNull(azureEndpoint.getData()) ? azureEndpoint.getData().getContent() : "");
                config.setModel(Objects.nonNull(azureDeployId.getData()) ? azureDeployId.getData().getContent() : "");
                break;
            case RESTAI:
                DataResult<Config> restAIApiKey = configService.find(RestAIClient.REST_AI_API_KEY);
                DataResult<Config> restAIApiHost = configService.find(RestAIClient.REST_AI_URL);
                DataResult<Config> restAIModel = configService.find(RestAIClient.REST_AI_MODEL);
                config.setApiKey(Objects.nonNull(restAIApiKey.getData()) ? restAIApiKey.getData().getContent() : "");
                config.setApiHost(Objects.nonNull(restAIApiHost.getData()) ? restAIApiHost.getData().getContent() : "");
                config.setModel(Objects.nonNull(restAIModel.getData()) ? restAIModel.getData().getContent() : "");
                break;
            case FASTCHATAI:
                DataResult<Config> fastChatApiKey = configService.find(FastChatAIClient.FASTCHAT_API_KEY);
                DataResult<Config> fastChatApiHost = configService.find(FastChatAIClient.FASTCHAT_HOST);
                DataResult<Config> fastChatModel = configService.find(FastChatAIClient.FASTCHAT_MODEL);
                config.setApiKey(Objects.nonNull(fastChatApiKey.getData()) ? fastChatApiKey.getData().getContent() : "");
                config.setApiHost(Objects.nonNull(fastChatApiHost.getData()) ? fastChatApiHost.getData().getContent() : "");
                config.setModel(Objects.nonNull(fastChatModel.getData()) ? fastChatModel.getData().getContent() : "");
                break;
            case WENXINAI:
                DataResult<Config> wenxinAccessToken = configService.find(WenxinAIClient.WENXIN_ACCESS_TOKEN);
                DataResult<Config> wenxinApiHost = configService.find(WenxinAIClient.WENXIN_HOST);
                config.setApiKey(Objects.nonNull(wenxinAccessToken.getData()) ? wenxinAccessToken.getData().getContent() : "");
                config.setApiHost(Objects.nonNull(wenxinApiHost.getData()) ? wenxinApiHost.getData().getContent() : "");
                break;
            case BAICHUANAI:
                DataResult<Config> baichuanApiKey = configService.find(BaichuanAIClient.BAICHUAN_API_KEY);
                DataResult<Config> baichuanSecretKey = configService.find(BaichuanAIClient.BAICHUAN_SECRET_KEY);
                DataResult<Config> baichuanApiHost = configService.find(BaichuanAIClient.BAICHUAN_HOST);
                DataResult<Config> baichuanModel = configService.find(BaichuanAIClient.BAICHUAN_MODEL);
                config.setApiKey(Objects.nonNull(baichuanApiKey.getData()) ? baichuanApiKey.getData().getContent() : "");
                config.setSecretKey(Objects.nonNull(baichuanSecretKey.getData()) ? baichuanSecretKey.getData().getContent() : "");
                config.setApiHost(Objects.nonNull(baichuanApiHost.getData()) ? baichuanApiHost.getData().getContent() : "");
                config.setModel(Objects.nonNull(baichuanModel.getData()) ? baichuanModel.getData().getContent() : "");
                break;
            case TONGYIQIANWENAI:
                DataResult<Config> tongyiApiKey = configService.find(TongyiChatAIClient.TONGYI_API_KEY);
                DataResult<Config> tongyiApiHost = configService.find(TongyiChatAIClient.TONGYI_HOST);
                DataResult<Config> tongyiModel = configService.find(TongyiChatAIClient.TONGYI_MODEL);
                config.setApiKey(Objects.nonNull(tongyiApiKey.getData()) ? tongyiApiKey.getData().getContent() : "");
                config.setApiHost(Objects.nonNull(tongyiApiHost.getData()) ? tongyiApiHost.getData().getContent() : "");
                config.setModel(Objects.nonNull(tongyiModel.getData()) ? tongyiModel.getData().getContent() : "");
                break;
            case ZHIPUAI:
                DataResult<Config> zhipuApiKey = configService.find(ZhipuChatAIClient.ZHIPU_API_KEY);
                DataResult<Config> zhipuApiHost = configService.find(ZhipuChatAIClient.ZHIPU_HOST);
                DataResult<Config> zhipuModel = configService.find(ZhipuChatAIClient.ZHIPU_MODEL);
                config.setApiKey(Objects.nonNull(zhipuApiKey.getData()) ? zhipuApiKey.getData().getContent() : "");
                config.setApiHost(Objects.nonNull(zhipuApiHost.getData()) ? zhipuApiHost.getData().getContent() : "");
                config.setModel(Objects.nonNull(zhipuModel.getData()) ? zhipuModel.getData().getContent() : "");
                break;
            default:
                break;
        }

        return DataResult.of(config);
    }

}

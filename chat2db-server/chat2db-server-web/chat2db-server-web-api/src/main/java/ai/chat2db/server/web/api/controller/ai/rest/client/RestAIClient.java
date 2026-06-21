
package ai.chat2db.server.web.api.controller.ai.rest.client;

import ai.chat2db.server.domain.api.model.Config;
import ai.chat2db.server.domain.api.service.ConfigService;
import ai.chat2db.server.web.api.util.ApplicationContextUtil;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * @author moji
 * @version : RestAIClient.java
 */
@Slf4j
public class RestAIClient {

    /**
     * Interface source selected by AI SQL
     */
    public static final String AI_SQL_SOURCE = "ai.sql.source";

    /**
     * Customized AI interface KEY
     */
    public static final String REST_AI_API_KEY = "rest.ai.apiKey";

    /**
     * Customized AI interface address
     */
    public static final String REST_AI_URL = "rest.ai.url";

    /**
     * Custom AI interface request method
     */
    public static final String REST_AI_STREAM_OUT = "rest.ai.stream";

    /**
     * Custom AI interface model
     */
    public static final String REST_AI_MODEL = "rest.ai.model";



    private static RestAIStreamClient REST_AI_STREAM_CLIENT;

    public static RestAIStreamClient getInstance() {
        if (REST_AI_STREAM_CLIENT != null) {
            return REST_AI_STREAM_CLIENT;
        } else {
            return singleton();
        }
    }

    private static RestAIStreamClient singleton() {
        if (REST_AI_STREAM_CLIENT == null) {
            synchronized (RestAIClient.class) {
                if (REST_AI_STREAM_CLIENT == null) {
                    refresh();
                }
            }
        }
        return REST_AI_STREAM_CLIENT;
    }

    /**
     * Refresh client
     */
    public static void refresh() {
        String apiUrl = "";
        String apiKey = "";
        String model = "";
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config apiHostConfig = configService.find(REST_AI_URL).getData();
        if (apiHostConfig != null) {
            apiUrl = apiHostConfig.getContent();
        }
        Config config = configService.find(REST_AI_API_KEY).getData();
        if (config != null) {
            apiKey = config.getContent();
        }
        Config deployConfig = configService.find(REST_AI_MODEL).getData();
        if (deployConfig != null && StringUtils.isNotBlank(deployConfig.getContent())) {
            model = deployConfig.getContent();
        }
        REST_AI_STREAM_CLIENT = RestAIStreamClient.builder().apiKey(apiKey).apiHost(apiUrl).model(model)
                .build();
    }

}

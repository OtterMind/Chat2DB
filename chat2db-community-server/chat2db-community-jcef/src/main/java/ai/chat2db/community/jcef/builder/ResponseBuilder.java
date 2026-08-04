package ai.chat2db.community.jcef.builder;

import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.console.ConsoleCodec;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import org.cef.callback.CefQueryCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class ResponseBuilder {
    private static final Logger log = LoggerFactory.getLogger(ResponseBuilder.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.registerModule(new AfterburnerModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static void buildSuccess(ConsoleResult result, CefQueryCallback callback) {
        try {
            String jsonResult = objectMapper.writeValueAsString(result);
            callback.success(jsonResult);
        } catch (Exception e) {
            log.error("Failed to build success response", e);
        }
    }


    public static void buildSuccessJcef(Map<String, Object> result, CefQueryCallback callback) {
        try {
            String jsonResult = objectMapper.writeValueAsString(result);
            callback.success(jsonResult);
        } catch (Exception e) {
            log.error("Failed to build success response", e);
        }
    }

    public static void buildError(ConsoleResult result, CefQueryCallback callback) {
        try {
            String jsonResult = JSON.toJSONString(result);
            String compressedResult = ConsoleCodec.compress(jsonResult);
            callback.failure(500, compressedResult);
        } catch (Exception e) {
            log.error("Failed to build error response", e);
        }
    }
}

package ai.chat2db.community.web.api.converter.ai;

import ai.chat2db.community.web.api.model.response.ai.AiToolResult;
import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Component;

@Component
public class AiToolResultSerializer {

    public String toJson(AiToolResult<?> result) {
        return JSON.toJSONString(result);
    }
}

package ai.chat2db.community.web.api.converter.ai;

import ai.chat2db.community.domain.api.model.ai.AiToolResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.stereotype.Component;

@Component
public class AiToolResultSerializer {

    public String toJson(AiToolResult<?> result) {
        return JSON.toJSONString(result, JSONWriter.Feature.WriteNulls);
    }
}

package ai.chat2db.community.domain.api.model.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiToolResult {

    private Boolean success;

    private String summary;

    private List<Object> data = new ArrayList<>();

    private String errorCode;

    public static AiToolResult success(String summary, List<?> data) {
        AiToolResult result = new AiToolResult();
        result.setSuccess(Boolean.TRUE);
        result.setSummary(summary);
        result.setData(copyData(data));
        return result;
    }

    public static AiToolResult failure(String summary, String errorCode) {
        AiToolResult result = new AiToolResult();
        result.setSuccess(Boolean.FALSE);
        result.setSummary(summary);
        result.setErrorCode(errorCode);
        return result;
    }

    private static List<Object> copyData(List<?> data) {
        if (data == null || data.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(data);
    }
}

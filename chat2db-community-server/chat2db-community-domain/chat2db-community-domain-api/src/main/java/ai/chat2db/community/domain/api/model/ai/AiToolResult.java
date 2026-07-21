package ai.chat2db.community.domain.api.model.ai;

import lombok.Data;

@Data
public class AiToolResult<T> {

    private Boolean success;

    private String summary;

    private T data;

    private String errorCode;

    public static <T> AiToolResult<T> success(String summary, T data) {
        AiToolResult<T> result = new AiToolResult<>();
        result.setSuccess(Boolean.TRUE);
        result.setSummary(summary);
        result.setData(data);
        return result;
    }

    public static <T> AiToolResult<T> failureWithCode(String errorCode, String summary) {
        AiToolResult<T> result = new AiToolResult<>();
        result.setSuccess(Boolean.FALSE);
        result.setSummary(summary);
        result.setErrorCode(errorCode);
        return result;
    }
}

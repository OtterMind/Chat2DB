package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.util.McpRuntimeStatus;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

@JcefAction(value = "get-mcp-status", method = "client-command")
public class GetMcpStatusHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        try {
            JSONObject request = JSON.parseObject(consoleMessage.getMessage());
            String operationId = request == null ? null : request.getString("operationId");
            ResponseBuilder.buildSuccessJcef(Map.of("data", McpRuntimeStatus.snapshot(operationId)), callback);
        } catch (Exception exception) {
            callback.failure(500, exception.getMessage());
        }
    }
}

package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.tools.config.SystemSettingConstant;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.util.McpRuntimeStatus;
import ai.chat2db.community.tools.util.SystemSettingsUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

@JcefAction(value = "set-mcp-enabled", method = "client-command")
public class SetMcpEnabledHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        try {
            JSONObject request = JSON.parseObject(consoleMessage.getMessage());
            if (request == null || !request.containsKey("enabled")) {
                callback.failure(400, "enabled is required");
                return;
            }
            String operationId = request.getString("operationId");
            boolean enabled = request.getBooleanValue("enabled");
            SystemSettingsUtil.setProperty(SystemSettingConstant.ENABLE_MCP, enabled);
            ResponseBuilder.buildSuccessJcef(Map.of("data", McpRuntimeStatus.snapshot(operationId)), callback);
        } catch (Exception exception) {
            callback.failure(500, exception.getMessage());
        }
    }
}

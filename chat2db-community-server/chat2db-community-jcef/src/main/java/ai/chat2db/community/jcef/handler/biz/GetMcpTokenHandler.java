package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.tools.util.SystemSettingsUtil;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

@JcefAction(value = "get-mcp-token", method = "client-command")
public class GetMcpTokenHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        try {
            ResponseBuilder.buildSuccessJcef(Map.of("data", SystemSettingsUtil.getOrCreateMcpAuthToken()), callback);
        } catch (Exception exception) {
            callback.failure(500, exception.getMessage());
        }
    }
}

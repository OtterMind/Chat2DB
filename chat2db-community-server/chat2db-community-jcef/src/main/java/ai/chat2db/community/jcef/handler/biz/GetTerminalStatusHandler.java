package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.terminal.TerminalSessionManager;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

@JcefAction(value = "get-terminal-status", method = "client-command")
public class GetTerminalStatusHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        JSONObject request = JSON.parseObject(consoleMessage.getMessage());
        Map<String, Object> status = TerminalSessionManager.status(request.getString("sessionId"));
        ResponseBuilder.buildSuccessJcef(Map.of("data", status), callback);
    }
}

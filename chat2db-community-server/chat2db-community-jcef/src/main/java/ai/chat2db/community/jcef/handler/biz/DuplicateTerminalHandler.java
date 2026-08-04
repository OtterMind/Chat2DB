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

@JcefAction(value = "duplicate-terminal", method = "client-command")
public class DuplicateTerminalHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {
        JSONObject request = JSON.parseObject(consoleMessage.getMessage());
        Map<String, Object> session = TerminalSessionManager.duplicate(
                request.getString("sessionId"),
                request.getIntValue("columns", 100),
                request.getIntValue("rows", 30)
        );
        ResponseBuilder.buildSuccessJcef(Map.of("data", session), callback);
    }
}

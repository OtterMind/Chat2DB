package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.terminal.TerminalSessionManager;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.util.List;
import java.util.Map;

@JcefAction(value = "kill-terminals", method = "client-command")
public class KillTerminalsHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        JSONObject request = JSON.parseObject(consoleMessage.getMessage());
        List<String> sessionIds = request.getList("sessionIds", String.class);
        TerminalSessionManager.kill(sessionIds == null ? List.of() : sessionIds);
        ResponseBuilder.buildSuccessJcef(Map.of("data", true), callback);
    }
}

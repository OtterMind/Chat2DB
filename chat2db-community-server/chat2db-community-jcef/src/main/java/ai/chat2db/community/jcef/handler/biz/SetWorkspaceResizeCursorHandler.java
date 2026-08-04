package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.handler.mouse.CursorHandler;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

@JcefAction(value = "set-workspace-resize-cursor", method = "client-command")
public class SetWorkspaceResizeCursorHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        JSONObject message = JSON.parseObject(consoleMessage.getMessage());
        CursorHandler.setForcedCursor(
                JcefContext.getInstance().getBrowser_(),
                message.getString("cursor"),
                message.getLongValue("sequence")
        );
        ResponseBuilder.buildSuccessJcef(Map.of("data", true), callback);
    }
}

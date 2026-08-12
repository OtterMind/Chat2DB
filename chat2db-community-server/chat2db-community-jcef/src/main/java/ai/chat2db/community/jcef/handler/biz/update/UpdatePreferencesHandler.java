package ai.chat2db.community.jcef.handler.biz.update;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.handler.biz.IJcefActionHandler;
import ai.chat2db.community.jcef.update.DesktopUpdaterRegistry;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

@JcefAction(value = "update-preferences", method = "client-command")
public class UpdatePreferencesHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        JSONObject request = JSON.parseObject(consoleMessage.getMessage());
        boolean saved = true;
        if (request != null && request.containsKey("receiveBeta")) {
            saved = DesktopUpdaterRegistry.get().setBetaEnabled(request.getBooleanValue("receiveBeta"));
        }
        ResponseBuilder.buildSuccessJcef(Map.of(
            "data", Map.of("saved", saved, "receiveBeta", DesktopUpdaterRegistry.get().isBetaEnabled())
        ), callback);
    }
}

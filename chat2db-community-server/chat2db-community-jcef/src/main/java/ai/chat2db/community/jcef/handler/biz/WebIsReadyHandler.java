package ai.chat2db.community.jcef.handler.biz;


import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.event.manager.FileOpenEventManager;
import ai.chat2db.community.jcef.utils.ApplicationExitCoordinator;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.callback.CefQueryCallback;

import java.util.Map;


@JcefAction(value = "handle-java-message-is-ready", method = "client-command")
public class WebIsReadyHandler implements IJcefActionHandler{
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {
        FileOpenEventManager.markAppReady();
        ApplicationExitCoordinator.markFrontendReady();
        ResponseBuilder.buildSuccessJcef(Map.of("data", true), callback);
    }
}

package ai.chat2db.community.jcef.handler.biz.windows;


import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.handler.biz.IJcefActionHandler;
import ai.chat2db.community.jcef.utils.ApplicationExitCoordinator;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.callback.CefQueryCallback;

import java.util.Map;


@JcefAction(value = "close-window", method = "client-command")
public class CloseWindowHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {
        ApplicationExitCoordinator.request(ApplicationExitCoordinator.ExitAction.CLOSE.name());
        ResponseBuilder.buildSuccessJcef(Map.of("data", true), callback);
    }
}

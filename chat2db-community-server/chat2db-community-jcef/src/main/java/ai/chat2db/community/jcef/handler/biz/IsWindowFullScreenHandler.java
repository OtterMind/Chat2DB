package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.frame.MainJFrame;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

@JcefAction(value = "is-window-full-screen", method = "client-command")
public class IsWindowFullScreenHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        ResponseBuilder.buildSuccessJcef(
                Map.of("data", MainJFrame.getInstance().isWindowFullScreen()),
                callback
        );
    }
}

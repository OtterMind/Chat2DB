package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.terminal.TerminalSessionManager;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.callback.CefQueryCallback;

@JcefAction(value = "get-terminal-capabilities", method = "client-command")
public class GetTerminalCapabilitiesHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        ResponseBuilder.buildSuccessJcef(TerminalSessionManager.capabilities(), callback);
    }
}

package ai.chat2db.community.jcef.handler.biz.update;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.handler.biz.IJcefActionHandler;
import ai.chat2db.community.jcef.update.DesktopUpdateRecoveryStatus;
import ai.chat2db.community.jcef.update.DesktopUpdaterRegistry;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

@JcefAction(value = "update-recovery-status", method = "client-command")
public class GetUpdateRecoveryStatusHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        DesktopUpdateRecoveryStatus status = DesktopUpdaterRegistry.get().recoveryStatus();
        ResponseBuilder.buildSuccessJcef(Map.of(
            "data", Map.of(
                "failed", status.failed(),
                "fromVersion", status.fromVersion(),
                "toVersion", status.toVersion()
            )
        ), callback);
    }
}

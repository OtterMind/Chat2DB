package ai.chat2db.community.jcef.handler.biz.update;


import ai.chat2db.community.jcef.update.DesktopUpdateCheckResult;
import ai.chat2db.community.jcef.update.DesktopUpdaterRegistry;
import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.enums.UpdatedStatus;
import ai.chat2db.community.jcef.handler.biz.IJcefActionHandler;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import lombok.extern.slf4j.Slf4j;
import org.cef.callback.CefQueryCallback;

import java.util.Map;


@Slf4j
@JcefAction(value = "app-check-update", method = "client-command")
public class AppCheckUpdateHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {
        DesktopUpdateCheckResult checkResult = DesktopUpdaterRegistry.get().appCheckUpdate();
        log.info(checkResult.toString());
        ResponseBuilder.buildSuccessJcef(
                Map.of("data", Map.of("status", checkResult.checkFailed() ? UpdatedStatus.UpdateFailed.getName()
                                : checkResult.needsUpdate() ? UpdatedStatus.Available.getName() : UpdatedStatus.NotAvailable.getName(),
                        "version", checkResult.needsUpdate() ? checkResult.version() : "",
                        "releaseNotes", checkResult.needsUpdate() ? checkResult.releaseNotes() : "",
                        "message", checkResult.checkFailed() ? "Unable to check for updates. Please try again." : "")
                ), callback);
    }
}

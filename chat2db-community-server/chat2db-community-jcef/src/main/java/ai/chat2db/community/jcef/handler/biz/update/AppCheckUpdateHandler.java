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

import java.util.LinkedHashMap;
import java.util.Map;


@Slf4j
@JcefAction(value = "app-check-update", method = "client-command")
public class AppCheckUpdateHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {
        DesktopUpdateCheckResult checkResult = DesktopUpdaterRegistry.get().appCheckUpdate();
        log.info(checkResult.toString());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", checkResult.checkFailed() ? UpdatedStatus.UpdateFailed.getName()
                : checkResult.needsUpdate() ? UpdatedStatus.Available.getName() : UpdatedStatus.NotAvailable.getName());
        data.put("version", checkResult.needsUpdate() ? checkResult.version() : "");
        data.put("releaseNotes", checkResult.needsUpdate() ? checkResult.releaseNotes() : "");
        data.put("message", checkResult.checkFailed() ? "Unable to check for updates. Please try again." : "");
        if (checkResult.releasePageUrl() != null) {
            data.put("releasePageUrl", checkResult.releasePageUrl());
        }
        if (checkResult.failureStage() != null) {
            data.put("failureStage", checkResult.failureStage().name());
        }
        if (checkResult.failureReason() != null) {
            data.put("failureReason", checkResult.failureReason().name());
        }
        ResponseBuilder.buildSuccessJcef(
                Map.of("data", data), callback);
    }
}

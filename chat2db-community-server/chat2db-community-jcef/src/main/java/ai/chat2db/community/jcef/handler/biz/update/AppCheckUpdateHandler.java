package ai.chat2db.community.jcef.handler.biz.update;


import ai.chat2db.community.jcef.update.DesktopUpdateCheckContext;
import ai.chat2db.community.jcef.update.DesktopUpdateCheckResult;
import ai.chat2db.community.jcef.update.DesktopUpdaterRegistry;
import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.enums.UpdatedStatus;
import ai.chat2db.community.jcef.handler.biz.IJcefActionHandler;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.updater.v2.telemetry.DesktopUsageTelemetry;
import ai.chat2db.community.updater.v2.telemetry.TelemetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.cef.callback.CefQueryCallback;

import java.io.File;
import java.util.Map;


@Slf4j
@JcefAction(value = "app-check-update", method = "client-command")
public class AppCheckUpdateHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {
        DesktopUpdateCheckContext context = DesktopUpdateCheckContext.parse(consoleMessage.getMessage());
        DesktopUpdateCheckResult checkResult = DesktopUpdaterRegistry.get().appCheckUpdate();
        log.info(checkResult.toString());
        reportUsageCheck(context, checkResult);
        String status = switch (checkResult.state()) {
            case AVAILABLE -> UpdatedStatus.Available.getName();
            // A downloaded package that still has to be installed: the client shows the install action.
            case READY_TO_INSTALL -> UpdatedStatus.Updated.getName();
            // The check could not reach the update source: the client reports a failure instead of
            // telling the user that no new version exists.
            case CHECK_FAILED -> UpdatedStatus.UpdateFailed.getName();
            case NOT_AVAILABLE -> UpdatedStatus.NotAvailable.getName();
        };
        ResponseBuilder.buildSuccessJcef(
                Map.of("data", Map.of("status", status,
                        "version", checkResult.state() == DesktopUpdateCheckResult.State.NOT_AVAILABLE
                                ? "" : String.valueOf(checkResult.version()))
                ), callback);
    }

    /** The updater owns the version file layout: {@code <app>/version.json}. */
    private String installedVersion() {
        return DesktopUpdaterRegistry.get().installedVersion();
    }

    /**
     * Reports the usage event that rides along this update check. Reporting is best effort: it must not
     * change the update check response, so every failure is contained here.
     */
    private void reportUsageCheck(DesktopUpdateCheckContext context, DesktopUpdateCheckResult checkResult) {
        try {
            // The reporting state lives next to the product config, never in a directory of its own.
            TelemetryConfig.configDirectory(ConfigUtils.getBasePath() + File.separator + "config");
            DesktopUsageTelemetry.get().reportCheck(context.trigger(), context.offlineActivation(),
                    installedVersion(), DesktopUpdaterRegistry.get().isBetaEnabled(),
                    checkResult.needsUpdate(), checkResult.version());
        } catch (Exception exception) {
            log.warn("usage reporting skipped: {}", exception.getMessage());
        }
    }
}

package ai.chat2db.community.jcef.handler.biz.update;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.handler.biz.IJcefActionHandler;
import ai.chat2db.community.jcef.update.DesktopUpdaterRegistry;
import ai.chat2db.community.jcef.update.IDesktopUpdater;
import ai.chat2db.community.jcef.utils.ApplicationExitCoordinator;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.util.Map;
import java.util.UUID;


@JcefAction(value = "restart-app", method = "client-command")
public class RestartAppHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        try {
            JSONObject request = JSON.parseObject(consoleMessage.getMessage());
            String operationId = request == null ? null : request.getString("operationId");
            if (operationId == null || operationId.isBlank()) {
                operationId = UUID.randomUUID().toString();
            }
            IDesktopUpdater updater = DesktopUpdaterRegistry.get();
            boolean accepted = ApplicationExitCoordinator.request(
                    ApplicationExitCoordinator.ExitAction.RESTART.name(),
                    operationId,
                    () -> prepareRestart(updater)
            );
            ResponseBuilder.buildSuccessJcef(Map.of(
                    "data", Map.of("operationId", operationId, "accepted", accepted)
            ), callback);
        } catch (Exception exception) {
            callback.failure(500, exception.getMessage());
        }
    }

    private boolean prepareRestart(IDesktopUpdater updater) {
        try {
            boolean accepted = updater.prepareRestart();
            if (accepted) {
                updater.exitCurrentProcessAfterResponse();
            }
            return accepted;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not prepare application restart", exception);
        }
    }
}

package ai.chat2db.community.jcef.handler.biz.windows;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.handler.biz.IJcefActionHandler;
import ai.chat2db.community.jcef.utils.ApplicationExitCoordinator;
import ai.chat2db.community.jcef.update.Updater;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

@JcefAction(value = "confirm-close-window", method = "client-command")
public class ConfirmCloseWindowHandler implements IJcefActionHandler {

    private final Runnable exitAfterResponse;

    public ConfirmCloseWindowHandler() {
        this(() -> Updater.getInstance().exitCurrentProcessAfterResponse());
    }

    ConfirmCloseWindowHandler(Runnable exitAfterResponse) {
        this.exitAfterResponse = exitAfterResponse;
    }

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        JSONObject request = JSON.parseObject(consoleMessage.getMessage());
        String operationId = request == null ? null : request.getString("operationId");
        ApplicationExitCoordinator.Confirmation confirmation = ApplicationExitCoordinator.confirmPending(operationId);
        ResponseBuilder.buildSuccessJcef(Map.of("data", confirmation.accepted()), callback);
        if (confirmation.accepted()
                && confirmation.action() == ApplicationExitCoordinator.ExitAction.INSTALL_UPDATE) {
            exitAfterResponse.run();
        }
    }
}

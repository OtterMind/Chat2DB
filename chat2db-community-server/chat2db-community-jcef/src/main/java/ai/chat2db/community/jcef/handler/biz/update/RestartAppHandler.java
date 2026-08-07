package ai.chat2db.community.jcef.handler.biz.update;


import ai.chat2db.community.jcef.update.Updater;
import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.handler.biz.IJcefActionHandler;
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
            Updater updater = Updater.getInstance();
            boolean accepted = updater.prepareRestart();
            ResponseBuilder.buildSuccessJcef(Map.of(
                    "data", Map.of("operationId", operationId, "accepted", accepted)
            ), callback);
            if (accepted) {
                updater.exitCurrentProcessAfterResponse();
            }
        } catch (Exception exception) {
            callback.failure(500, exception.getMessage());
        }
    }
}

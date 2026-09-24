package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.sqlx.SqlxBridgeRegistry;
import ai.chat2db.community.tools.sqlx.SqlxException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

/**
 * Starts downloading and installing the official SQLX release in the background.
 */
@JcefAction(value = "install-sqlx", method = "client-command")
public class InstallSqlxHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        try {
            JSONObject request = JSON.parseObject(consoleMessage.getMessage());
            String operationId = request == null ? null : request.getString("operationId");
            String version = request == null ? null : request.getString("version");
            ResponseBuilder.buildSuccessJcef(
                    Map.of("data", SqlxBridgeRegistry.getBridge().install(operationId, version)), callback);
        } catch (SqlxException exception) {
            callback.failure(400, exception.getMessage());
        } catch (RuntimeException exception) {
            callback.failure(500, exception.getMessage());
        }
    }
}

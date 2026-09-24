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
 * Adopts an executable the user selected, after confirming it is OtterMind SQLX.
 */
@JcefAction(value = "set-sqlx-binary", method = "client-command")
public class SetSqlxBinaryHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        try {
            JSONObject request = JSON.parseObject(consoleMessage.getMessage());
            String path = request == null ? null : request.getString("path");
            ResponseBuilder.buildSuccessJcef(
                    Map.of("data", SqlxBridgeRegistry.getBridge().useBinary(path)), callback);
        } catch (SqlxException exception) {
            callback.failure(400, exception.getMessage());
        } catch (RuntimeException exception) {
            callback.failure(500, exception.getMessage());
        }
    }
}

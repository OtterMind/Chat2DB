package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.sqlx.SqlxBridgeRegistry;
import ai.chat2db.community.tools.sqlx.SqlxException;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

/**
 * Reports the local SQLX command line without touching the network.
 */
@JcefAction(value = "get-sqlx-status", method = "client-command")
public class GetSqlxStatusHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        try {
            ResponseBuilder.buildSuccessJcef(Map.of("data", SqlxBridgeRegistry.getBridge().status()), callback);
        } catch (SqlxException exception) {
            callback.failure(400, exception.getMessage());
        } catch (RuntimeException exception) {
            callback.failure(500, exception.getMessage());
        }
    }
}

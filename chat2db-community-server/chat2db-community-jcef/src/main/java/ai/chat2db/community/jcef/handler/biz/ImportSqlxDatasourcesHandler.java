package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.sqlx.SqlxBridgeRegistry;
import ai.chat2db.community.tools.sqlx.SqlxException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.util.List;
import java.util.Map;

/**
 * Copies the selected Chat2DB datasources into SQLX.
 * <p>
 * The renderer only sends datasource IDs; credentials never cross this boundary.
 */
@JcefAction(value = "import-sqlx-datasources", method = "client-command")
public class ImportSqlxDatasourcesHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        try {
            JSONObject request = JSON.parseObject(consoleMessage.getMessage());
            JSONArray ids = request == null ? null : request.getJSONArray("ids");
            List<Long> datasourceIds = ids == null ? List.of() : ids.toJavaList(Long.class);
            ResponseBuilder.buildSuccessJcef(
                    Map.of("data", SqlxBridgeRegistry.getBridge().importDatasources(datasourceIds)), callback);
        } catch (SqlxException exception) {
            callback.failure(400, exception.getMessage());
        } catch (RuntimeException exception) {
            callback.failure(500, exception.getMessage());
        }
    }
}

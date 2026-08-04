package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.util.Map;

@JcefAction(value = "read-sql-directory-preview", method = "client-command")
public class ReadSqlDirectoryPreviewHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {
        JSONObject request = JSON.parseObject(consoleMessage.getMessage());
        Map<String, Object> preview = SqlDirectoryTreeStore.readPreview(
                request.getString("rootToken"),
                request.getString("relativePath")
        );
        ResponseBuilder.buildSuccessJcef(Map.of("data", preview), callback);
    }
}

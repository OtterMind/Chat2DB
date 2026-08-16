package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.terminal.TerminalSessionManager;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.nio.file.Path;
import java.util.Map;

@JcefAction(value = "create-sql-directory-terminal", method = "client-command")
public class CreateSqlDirectoryTerminalHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {
        JSONObject request = JSON.parseObject(consoleMessage.getMessage());
        Path directory = Path.of(SqlDirectoryTreeStore.getDirectoryPath(
                request.getString("rootToken"),
                request.getString("relativePath")
        ));
        Map<String, Object> session = TerminalSessionManager.create(
                directory,
                request.getIntValue("columns", 100),
                request.getIntValue("rows", 30),
                request.getString("shellId")
        );
        ResponseBuilder.buildSuccessJcef(Map.of("data", session), callback);
    }
}

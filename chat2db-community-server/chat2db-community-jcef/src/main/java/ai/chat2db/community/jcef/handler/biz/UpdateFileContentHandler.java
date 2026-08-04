package ai.chat2db.community.jcef.handler.biz;


import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.Objects;


@JcefAction(value = "update-file-content", method = "client-command")
public class UpdateFileContentHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        try {
            JSONObject jsonObject = JSON.parseObject(consoleMessage.getMessage());
            String filePath = jsonObject.getString("filePath");
            String fileContent = jsonObject.getString("fileContent");
            String charsetName = jsonObject.getString("charset");
            Charset charset = charsetName == null || charsetName.isBlank() ? null : Charset.forName(charsetName);
            Boolean bom = jsonObject.getBoolean("bom");
            Map<String, Object> result = OSOperateUtil.updateFileContent(filePath, fileContent, charset, bom);
            Object path = result.get("path");
            if (Objects.nonNull(path)) {
                ResponseBuilder.buildSuccessJcef(Map.of("data", true), callback);
            }
        } catch (Exception exception) {
            callback.failure(500, exception.getMessage());
        }
    }
}

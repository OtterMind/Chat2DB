package ai.chat2db.community.jcef.handler.biz;


import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.cef.callback.CefQueryCallback;

import java.nio.charset.Charset;
import java.util.Map;


@JcefAction(value = "read-file", method = "client-command")
public class ReadFileHandler implements IJcefActionHandler {

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        try {
            Charset charset = null;
            JSONObject jsonObject = JSON.parseObject(consoleMessage.getMessage());
            String path = jsonObject.getString("path");
            String charsets = jsonObject.getString("charsets");
            if (StringUtils.isNotBlank(charsets)) {
                charset = Charset.forName(charsets);
            }
            Map<String, Object> result = OSOperateUtil.openLocalFile(path, charset);
            ResponseBuilder.buildSuccessJcef(Map.of("data", result), callback);
        } catch (Exception exception) {
            callback.failure(500, exception.getMessage());
        }
    }
}

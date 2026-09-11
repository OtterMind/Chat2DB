package ai.chat2db.community.jcef.handler.biz;


import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.menus.MenuI18n;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.callback.CefQueryCallback;

import java.util.Map;


@JcefAction(value = "select-directory", method = "client-command")
public class SelectDirectoryHandler implements IJcefActionHandler {
    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {
        String fileName = OSOperateUtil.openNativeDirChooser(JcefContext.getInstance().getFrame_(), MenuI18n.getString("fileChooser.select.dir.title"));
        ResponseBuilder.buildSuccessJcef(java.util.Collections.singletonMap("data", fileName), callback);
    }

}

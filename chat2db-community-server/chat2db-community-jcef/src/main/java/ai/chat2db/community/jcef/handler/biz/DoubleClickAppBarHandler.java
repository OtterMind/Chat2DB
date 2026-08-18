package ai.chat2db.community.jcef.handler.biz;


import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.frame.MainJFrame;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.jcef.utils.SystemSettingsUtil;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.OS;
import org.cef.callback.CefQueryCallback;

import javax.swing.*;
import java.util.Map;


@JcefAction(value = "double-click-app-bar", method = "client-command")
public class DoubleClickAppBarHandler implements IJcefActionHandler{

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {
        MainJFrame mainJFrame = JcefContext.getInstance().getFrame_();
        if (OS.isWindows()) {
            boolean maximized = OSOperateUtil.windowsToggleMaximized(mainJFrame);
            ResponseBuilder.buildSuccessJcef(Map.of("data", maximized), callback);
            return;
        }

        int extendedState = mainJFrame.getExtendedState();
        if (extendedState != JFrame.MAXIMIZED_BOTH) {
            SystemSettingsUtil.maximizeWindowAndSaveWindowInfo(mainJFrame);
        }
        if (extendedState == JFrame.MAXIMIZED_BOTH) {
            SystemSettingsUtil.recoverWindowFromMaxState(mainJFrame);
        }
        ResponseBuilder.buildSuccessJcef(
                Map.of("data", mainJFrame.getExtendedState() == JFrame.MAXIMIZED_BOTH),
                callback
        );
    }
}

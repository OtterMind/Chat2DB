package ai.chat2db.community.jcef.handler.biz;


import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.menus.MenuI18n;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefRunFileDialogCallback;
import org.cef.handler.CefDialogHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Vector;


@JcefAction(value = "save-file", method = "client-command")
public class SaveFIleHandler implements IJcefActionHandler {
    private static final Logger log = LoggerFactory.getLogger(SaveFIleHandler.class);

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {
        JSONObject jsonObject = JSON.parseObject(consoleMessage.getMessage());
        String fileName = jsonObject.getString("fileName");
        String fileContent = jsonObject.getString("fileContent");
        String fileType = jsonObject.getString("fileType");
        String defaultFileName = normalizeDefaultFileName(fileName, fileType);
        String title = MenuI18n.getString("fileChooser.select.file.title");
        CefBrowser browser = JcefContext.getInstance().getBrowser_();
        if (browser != null && openByJcefSaveDialog(browser, title, defaultFileName, fileContent, fileType, callback)) {
            return;
        }

        String filePath = OSOperateUtil.openNativeSaveFileChooser(
                JcefContext.getInstance().getFrame_(),
                title,
                defaultFileName
        );
        if (filePath == null) {
            respondWithCancelled(callback);
            return;
        }

        saveAndRespond(filePath, fileContent, fileType, callback);
    }

    boolean openByJcefSaveDialog(CefBrowser browser,
                                 String title,
                                 String defaultFileName,
                                 String fileContent,
                                 String fileType,
                                 CefQueryCallback callback) {
        Vector<String> acceptFilters = new Vector<>();
        String normalizedFileType = normalizeFileType(fileType);
        if (!normalizedFileType.isBlank()) {
            acceptFilters.add("." + normalizedFileType);
        }
        try {
            browser.runFileDialog(
                    CefDialogHandler.FileDialogMode.FILE_DIALOG_SAVE,
                    title,
                    defaultFileName,
                    acceptFilters,
                    new CefRunFileDialogCallback() {
                        @Override
                        public void onFileDialogDismissed(Vector<String> filePaths) {
                            if (filePaths == null || filePaths.isEmpty()) {
                                respondWithCancelled(callback);
                                return;
                            }
                            saveAndRespond(filePaths.firstElement(), fileContent, fileType, callback);
                        }
                    }
            );
            return true;
        } catch (RuntimeException exception) {
            log.warn("Failed to open JCEF save dialog, falling back to the native chooser", exception);
            return false;
        }
    }

    private void saveAndRespond(String filePath, String fileContent, String fileType, CefQueryCallback callback) {
        try {
            Map<String, Object> result = OSOperateUtil.saveFile(filePath, fileContent, fileType);
            ResponseBuilder.buildSuccessJcef(Map.of("data", result), callback);
        } catch (Exception exception) {
            log.error("Failed to save file: {}", filePath, exception);
            callback.failure(500, "Failed to save file");
        }
    }

    private void respondWithCancelled(CefQueryCallback callback) {
        Map<String, Object> response = new HashMap<>();
        response.put("data", null);
        ResponseBuilder.buildSuccessJcef(response, callback);
    }

    private String normalizeDefaultFileName(String fileName, String fileType) {
        String normalizedFileName = fileName == null || fileName.isBlank() ? "untitled" : fileName.trim();
        String normalizedFileType = normalizeFileType(fileType);
        if (normalizedFileName.toLowerCase().endsWith("." + normalizedFileType.toLowerCase())) {
            return normalizedFileName;
        }
        return normalizedFileName + "." + normalizedFileType;
    }

    private String normalizeFileType(String fileType) {
        String normalizedFileType = fileType == null || fileType.isBlank() ? "sql" : fileType.trim();
        return normalizedFileType.startsWith(".") ? normalizedFileType.substring(1) : normalizedFileType;
    }
}

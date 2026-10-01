package ai.chat2db.community.jcef.handler.biz;


import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.tuple.Pair;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefRunFileDialogCallback;
import org.cef.handler.CefDialogHandler;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Vector;


@JcefAction(value = "select-file", method = "client-command")
public class SelectFileHandler implements IJcefActionHandler {
    private static final String REQUEST_KEY_MULTIPLE = "multiple";
    private static final String REQUEST_KEY_FILE_SIZE = "fileSize";
    private static final String REQUEST_KEY_FILE_TYPE_LIST = "fileTypeList";
    private static final String RESPONSE_KEY_DATA = "data";
    private static final String RESPONSE_KEY_FILE_PATH = "filePath";
    private static final String RESPONSE_KEY_FILE_NAME = "fileName";
    private static final String RESPONSE_KEY_FILE_SIZE = "fileSize";
    private static final String DEFAULT_DIALOG_TITLE = "Select File";
    private static final String DEFAULT_FILE_PATH = "";
    private static final String FILE_EXTENSION_PREFIX = ".";
    private static final String EXTENSION_DELIMITER = ",";
    private static final String CEF_EXTENSION_DELIMITER = ";";
    private static final String CEF_FILTER_SEPARATOR = "|";
    private static final String SELECTED_FILES_ACCEPT_FILTER_DESCRIPTION = "Selected Files";

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {

        String message = consoleMessage.getMessage();
        JSONObject jsonObject = JSON.parseObject(message);
        Boolean multiple = jsonObject.getBoolean(REQUEST_KEY_MULTIPLE);
        long maxSizeMB = jsonObject.getLongValue(REQUEST_KEY_FILE_SIZE);
        List<String> fileTypeList = parseFileTypeList(jsonObject.get(REQUEST_KEY_FILE_TYPE_LIST));
        CefBrowser browser = JcefContext.getInstance().getBrowser_();
        if (browser != null && openByJcefFileDialog(browser, fileTypeList, Boolean.TRUE.equals(multiple), callback)) {
            return;
        }
        openByNativeFileChooser(fileTypeList, maxSizeMB, callback);
    }

    private List<String> parseFileTypeList(Object value) {
        if (value == null) {
            return Lists.newArrayList();
        }
        if (value instanceof Iterable<?>) {
            return toFileTypeList((Iterable<?>) value);
        }
        if (value instanceof JSONObject) {
            return parseIndexedFileTypeObject((JSONObject) value);
        }
        if (value instanceof String) {
            return parseFileTypeText((String) value);
        }
        List<String> fileTypes = Lists.newArrayList();
        addFileType(fileTypes, value);
        return fileTypes;
    }

    private List<String> parseFileTypeText(String value) {
        if (value == null || value.isBlank()) {
            return Lists.newArrayList();
        }
        String text = value.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            return toFileTypeList(JSON.parseArray(text));
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            return parseIndexedFileTypeObject(JSON.parseObject(text));
        }
        List<String> fileTypes = Lists.newArrayList();
        for (String fileType : text.split(EXTENSION_DELIMITER)) {
            addFileType(fileTypes, fileType);
        }
        return fileTypes;
    }

    private List<String> parseIndexedFileTypeObject(JSONObject value) {
        List<Map.Entry<String, Object>> entries = Lists.newArrayList(value.entrySet());
        entries.sort(this::compareIndexedEntry);
        List<String> fileTypes = Lists.newArrayList();
        for (Map.Entry<String, Object> entry : entries) {
            addFileType(fileTypes, entry.getValue());
        }
        return fileTypes;
    }

    private List<String> toFileTypeList(Iterable<?> values) {
        List<String> fileTypes = Lists.newArrayList();
        for (Object value : values) {
            addFileType(fileTypes, value);
        }
        return fileTypes;
    }

    private void addFileType(List<String> fileTypes, Object value) {
        if (value == null) {
            return;
        }
        String fileType = String.valueOf(value).trim();
        if (!fileType.isBlank()) {
            fileTypes.add(fileType);
        }
    }

    private int compareIndexedEntry(Map.Entry<String, Object> left, Map.Entry<String, Object> right) {
        int leftIndex = parseIndex(left.getKey());
        int rightIndex = parseIndex(right.getKey());
        if (leftIndex != rightIndex) {
            return Integer.compare(leftIndex, rightIndex);
        }
        return left.getKey().compareTo(right.getKey());
    }

    private int parseIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean openByJcefFileDialog(CefBrowser browser, List<String> fileTypeList, boolean multiple, CefQueryCallback callback) {
        Vector<String> acceptFilters = buildAcceptFilters(fileTypeList);
        CefDialogHandler.FileDialogMode mode = multiple
                ? CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE
                : CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN;
        try {
            browser.runFileDialog(mode, DEFAULT_DIALOG_TITLE, DEFAULT_FILE_PATH, acceptFilters, new CefRunFileDialogCallback() {
                @Override
                public void onFileDialogDismissed(Vector<String> filePaths) {
                    buildFileDialogResponse(filePaths, callback);
                }
            });
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Vector<String> buildAcceptFilters(List<String> fileTypeList) {
        Vector<String> acceptFilters = new Vector<>();
        List<String> extensions = Lists.newArrayList();
        fileTypeList.forEach(fileType -> Optional.ofNullable(toAcceptFilter(fileType)).ifPresent(extensions::add));
        if (!extensions.isEmpty()) {
            acceptFilters.add(SELECTED_FILES_ACCEPT_FILTER_DESCRIPTION
                    + CEF_FILTER_SEPARATOR
                    + String.join(CEF_EXTENSION_DELIMITER, extensions));
        }
        return acceptFilters;
    }

    private String toAcceptFilter(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return null;
        }
        return fileType.startsWith(FILE_EXTENSION_PREFIX) ? fileType : FILE_EXTENSION_PREFIX + fileType;
    }

    private void buildFileDialogResponse(Vector<String> filePaths, CefQueryCallback callback) {
        if (filePaths == null || filePaths.isEmpty()) {
            ResponseBuilder.buildSuccessJcef(buildResponseData(null), callback);
            return;
        }
        List<Map<@Nullable Object, @Nullable Object>> results = Lists.newArrayList();
        for (String filePath : filePaths) {
            results.add(buildFileSelection(filePath));
        }
        ResponseBuilder.buildSuccessJcef(buildResponseData(results), callback);
    }

    /**
     * The renderer enforces the total selection size cap from {@code fileSize}; the native chooser
     * and the CEF dialog must both report it in bytes or that cap silently stops applying.
     */
    private Map<@Nullable Object, @Nullable Object> buildFileSelection(String filePath) {
        HashMap<@Nullable Object, @Nullable Object> result = Maps.newHashMap();
        File file = new File(filePath);
        result.put(RESPONSE_KEY_FILE_PATH, filePath);
        result.put(RESPONSE_KEY_FILE_NAME, file.getName());
        result.put(RESPONSE_KEY_FILE_SIZE, file.isFile() ? file.length() : null);
        return result;
    }

    /**
     * The native chooser can only return one file, so a multiple request degrades to a single
     * selection. The renderer adds selections incrementally, so repeated calls still accumulate.
     */
    private void openByNativeFileChooser(List<String> fileTypeList, long maxSizeMB, CefQueryCallback callback) {
        Pair<String, String> pair = OSOperateUtil.openNativeFileChooser(JcefContext.getInstance().getFrame_(),
                null,
                String.join(EXTENSION_DELIMITER, fileTypeList),
                maxSizeMB
        );
        if (pair == null || pair.getLeft() == null) {
            ResponseBuilder.buildSuccessJcef(buildResponseData(null), callback);
            return;
        }
        ResponseBuilder.buildSuccessJcef(buildResponseData(Lists.newArrayList(buildFileSelection(pair.getLeft()))), callback);
    }

    private Map<String, Object> buildResponseData(@Nullable Object data) {
        Map<String, Object> response = Maps.newHashMap();
        response.put(RESPONSE_KEY_DATA, data);
        return response;
    }
}

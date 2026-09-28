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
import org.apache.commons.lang3.tuple.Pair;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefRunFileDialogCallback;
import org.cef.handler.CefDialogHandler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

@JcefAction(value = "select-tls-file-content", method = "client-command")
public class SelectTlsFileContentHandler implements IJcefActionHandler {

    private static final String MODE_BASE64 = "base64";
    private static final long MAX_TLS_FILE_SIZE_BYTES = 10L * 1024L * 1024L;
    private static final long BYTES_PER_MEBIBYTE = 1024L * 1024L;
    private static final Set<String> PEM_EXTENSIONS = Set.of("pem");
    private static final Set<String> STORE_EXTENSIONS = Set.of("jks", "p12", "pfx", "pkcs12");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pem", "jks", "p12", "pfx", "pkcs12");
    private static final String GENERIC_FILE_ERROR = "Unable to read selected TLS file.";
    private static final String REQUEST_KEY_FILE_TYPE_LIST = "fileTypeList";
    private static final String REQUEST_KEY_FILE_SIZE = "fileSize";
    private static final String REQUEST_KEY_MODE = "mode";
    private static final String DEFAULT_DIALOG_TITLE = "Select TLS File";
    private static final String DEFAULT_FILE_PATH = "";
    private static final String EXTENSION_DELIMITER = ",";
    private static final String CEF_EXTENSION_DELIMITER = ";";
    private static final String CEF_FILTER_SEPARATOR = "|";
    private static final String SELECTED_FILES_ACCEPT_FILTER_DESCRIPTION = "TLS Files";

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) {
        try {
            JSONObject jsonObject = JSON.parseObject(consoleMessage.getMessage());
            List<String> fileTypeList = parseFileTypeList(jsonObject.get(REQUEST_KEY_FILE_TYPE_LIST));
            String mode = jsonObject.getString(REQUEST_KEY_MODE);
            long maxSizeMB = jsonObject.getLongValue(REQUEST_KEY_FILE_SIZE);
            long maxSizeBytes = resolveMaxBytes(maxSizeMB);
            CefBrowser browser = JcefContext.getInstance().getBrowser_();
            if (browser != null && openByJcefFileDialog(browser, fileTypeList, mode, maxSizeBytes, callback)) {
                return;
            }
            openByNativeFileChooser(fileTypeList, maxSizeBytes, mode, callback);
        } catch (Exception exception) {
            callback.failure(500, GENERIC_FILE_ERROR);
        }
    }

    static Map<String, Object> readTlsFile(Path file, String mode, long maxSizeBytes) throws Exception {
        try {
            if (file == null || maxSizeBytes <= 0 || maxSizeBytes > MAX_TLS_FILE_SIZE_BYTES) {
                throw new IllegalArgumentException();
            }
            Path normalized = file.toAbsolutePath().normalize();
            String extension = extension(normalized);
            Set<String> modeExtensions = MODE_BASE64.equalsIgnoreCase(mode) ? STORE_EXTENSIONS : PEM_EXTENSIONS;
            if (!modeExtensions.contains(extension)) {
                throw new IllegalArgumentException();
            }
            Path regularFile = normalized.toRealPath();
            if (!Files.isRegularFile(regularFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException();
            }
            long size = Files.size(regularFile);
            if (size > maxSizeBytes) {
                throw new IllegalArgumentException();
            }
            byte[] bytes;
            try (InputStream inputStream = Files.newInputStream(regularFile)) {
                bytes = inputStream.readNBytes(Math.toIntExact(maxSizeBytes + 1));
            }
            if (bytes.length > maxSizeBytes) {
                throw new IllegalArgumentException();
            }
            Map<String, Object> result = new HashMap<>();
            result.put("fileName", regularFile.getFileName().toString());
            result.put("size", size);
            result.put("content", MODE_BASE64.equalsIgnoreCase(mode)
                    ? Base64.getEncoder().encodeToString(bytes)
                    : new String(bytes, StandardCharsets.UTF_8));
            return result;
        } catch (Exception exception) {
            throw new TlsFileReadException();
        }
    }

    private boolean openByJcefFileDialog(CefBrowser browser, List<String> fileTypeList, String mode,
                                         long maxSizeBytes,
                                         CefQueryCallback callback) {
        try {
            browser.runFileDialog(CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN, DEFAULT_DIALOG_TITLE,
                    DEFAULT_FILE_PATH, buildAcceptFilters(fileTypeList), new CefRunFileDialogCallback() {
                        @Override
                        public void onFileDialogDismissed(Vector<String> filePaths) {
                            try {
                                if (filePaths == null || filePaths.isEmpty()) {
                                    ResponseBuilder.buildSuccessJcef(Map.of("data", null), callback);
                                    return;
                                }
                                ResponseBuilder.buildSuccessJcef(
                                        Map.of("data", readTlsFile(
                                                Path.of(filePaths.firstElement()), mode, maxSizeBytes)),
                                        callback);
                            } catch (Exception exception) {
                                callback.failure(500, GENERIC_FILE_ERROR);
                            }
                        }
                    });
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void openByNativeFileChooser(List<String> fileTypeList, long maxSizeBytes, String mode,
                                         CefQueryCallback callback) {
        try {
            Pair<String, String> pair = OSOperateUtil.openNativeFileChooser(JcefContext.getInstance().getFrame_(),
                    null, String.join(EXTENSION_DELIMITER, fileTypeList),
                    Math.max(1L, maxSizeBytes / BYTES_PER_MEBIBYTE));
            if (pair == null || pair.getLeft() == null) {
                ResponseBuilder.buildSuccessJcef(Map.of("data", null), callback);
                return;
            }
            ResponseBuilder.buildSuccessJcef(
                    Map.of("data", readTlsFile(Path.of(pair.getLeft()), mode, maxSizeBytes)), callback);
        } catch (Exception exception) {
            callback.failure(500, GENERIC_FILE_ERROR);
        }
    }

    private Vector<String> buildAcceptFilters(List<String> fileTypeList) {
        Vector<String> acceptFilters = new Vector<>();
        List<String> extensions = Lists.newArrayList();
        fileTypeList.forEach(fileType -> {
            if (fileType != null && !fileType.isBlank()) {
                extensions.add(fileType.startsWith(".") ? fileType : "." + fileType);
            }
        });
        if (!extensions.isEmpty()) {
            acceptFilters.add(SELECTED_FILES_ACCEPT_FILTER_DESCRIPTION
                    + CEF_FILTER_SEPARATOR
                    + String.join(CEF_EXTENSION_DELIMITER, extensions));
        }
        return acceptFilters;
    }

    private List<String> parseFileTypeList(Object value) {
        if (!(value instanceof Iterable<?> values)) {
            return Lists.newArrayList();
        }
        List<String> fileTypes = Lists.newArrayList();
        for (Object item : values) {
            if (item != null && !String.valueOf(item).isBlank()) {
                String extension = String.valueOf(item).replaceFirst("^\\.", "").toLowerCase(Locale.ROOT);
                if (ALLOWED_EXTENSIONS.contains(extension)) {
                    fileTypes.add(extension);
                }
            }
        }
        return fileTypes;
    }

    private static long resolveMaxBytes(long requestedMegabytes) {
        if (requestedMegabytes <= 0) {
            return MAX_TLS_FILE_SIZE_BYTES;
        }
        try {
            return Math.min(MAX_TLS_FILE_SIZE_BYTES, Math.multiplyExact(requestedMegabytes, BYTES_PER_MEBIBYTE));
        } catch (ArithmeticException exception) {
            return MAX_TLS_FILE_SIZE_BYTES;
        }
    }

    private static String extension(Path file) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return "";
        }
        String name = fileName.toString();
        int delimiter = name.lastIndexOf('.');
        return delimiter < 0 ? "" : name.substring(delimiter + 1).toLowerCase(Locale.ROOT);
    }

    private static final class TlsFileReadException extends Exception {
        private TlsFileReadException() {
            super(GENERIC_FILE_ERROR);
        }
    }
}

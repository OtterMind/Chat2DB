package ai.chat2db.community.jcef.utils;


import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.event.EventBus;
import ai.chat2db.community.jcef.event.OpenFileManagerEvent;
import ai.chat2db.community.jcef.handler.biz.GetJarPathHandler;
import ai.chat2db.community.jcef.handler.file.NativeFileChooser;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.cef.CefApp;
import org.cef.OS;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.Preferences;


@Slf4j
public class OSOperateUtil {


    public static void openFileManager(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return;

            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                Runtime.getRuntime().exec("explorer /select," + file.getAbsolutePath());
            } else if (os.contains("mac")) {
                revealInFinder(file);
            } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                try {
                    Runtime.getRuntime().exec(new String[]{"xdg-open", file.getParent()});
                } catch (IOException e) {
                    Runtime.getRuntime().exec(new String[]{"nautilus", "--select", file.getAbsolutePath()});
                }
            } else {
                Desktop.getDesktop().open(file.getParentFile());
            }
            EventBus.getInstance().publish(new OpenFileManagerEvent(filePath));
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    private static void revealInFinder(File file) throws IOException {
        Process revealProcess = new ProcessBuilder("open", "-R", file.getAbsolutePath()).start();
        try {
            if (revealProcess.waitFor() != 0) {
                throw new IOException("Finder could not reveal file: " + file.getAbsolutePath());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while revealing file in Finder", e);
        }
        new ProcessBuilder("open", "-a", "Finder").start();
    }

    public static void openTerminal(String directoryPath) throws IOException {
        if (directoryPath == null || directoryPath.isBlank()) {
            throw new IllegalArgumentException("Directory path is required");
        }

        File directory = new File(directoryPath);
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IllegalArgumentException("Directory path is not available");
        }

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            Runtime.getRuntime().exec(new String[]{"open", "-a", "Terminal", directory.getAbsolutePath()});
            return;
        }

        if (os.contains("win")) {
            Runtime.getRuntime().exec(new String[]{
                    "cmd", "/c", "start", "", "cmd", "/K", "cd", "/d", directory.getAbsolutePath()
            });
            return;
        }

        if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            String[] terminals = {"x-terminal-emulator", "gnome-terminal", "konsole", "xfce4-terminal", "xterm"};
            IOException lastException = null;
            for (String terminal : terminals) {
                try {
                    new ProcessBuilder(terminal)
                            .directory(directory)
                            .start();
                    return;
                } catch (IOException exception) {
                    lastException = exception;
                }
            }
            throw lastException == null ? new IOException("No terminal application is available") : lastException;
        }

        throw new IOException("Unsupported operating system for terminal");
    }


    public static void windowsMax(Frame frame) throws InvocationTargetException, InterruptedException {
        onEventDispatchThread(() -> {
            frame.setExtendedState(frame.getExtendedState() | Frame.MAXIMIZED_BOTH);
            return null;
        });
    }


    public static void windowsMin(Frame frame) throws InvocationTargetException, InterruptedException {
        onEventDispatchThread(() -> {
            frame.setExtendedState(frame.getExtendedState() | Frame.ICONIFIED);
            return null;
        });
    }


    public static boolean windowsToggleMaximized(Frame frame) throws InvocationTargetException, InterruptedException {
        return onEventDispatchThread(() -> {
            int state = frame.getExtendedState();
            int nextState = toggleMaximizedState(state);
            frame.setExtendedState(nextState);
            return isMaximizedState(nextState);
        });
    }


    public static void closeWindows(Frame frameToClose) {
        SystemSettingsUtil.saveWindowsInfo();
        frameToClose.dispose();
        CefApp cefApp = JcefContext.getInstance().getCefApp_();
        if (cefApp != null && CefApp.getState() != CefApp.CefAppState.TERMINATED) {
            log.info("Disposing CefApp during exit...");
            cefApp.dispose();
        }
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                log.info("CefApp is shutting down... {}", Thread.currentThread().getName());
                System.exit(0);
            }
        }, 3000);
    }


    public static boolean isWindowMaximized(Frame frame) throws InvocationTargetException, InterruptedException {
        return onEventDispatchThread(() -> isMaximizedState(frame.getExtendedState()));
    }


    static boolean isMaximizedState(int state) {
        return (state & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
    }


    static int toggleMaximizedState(int state) {
        if ((state & Frame.ICONIFIED) != 0) {
            return state & ~Frame.ICONIFIED;
        }
        return isMaximizedState(state) ? state & ~Frame.MAXIMIZED_BOTH : state | Frame.MAXIMIZED_BOTH;
    }


    private static <T> T onEventDispatchThread(Supplier<T> operation)
            throws InvocationTargetException, InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(operation.get()));
        return result.get();
    }


    public static String readFile(String filePath, String charset) {
        if (Files.notExists(Paths.get(filePath))) {
            throw new BusinessException("File not found: " + filePath);
        }
        String result;
        try {
            result = FileUtils.readFileToString(new File(filePath), charset);
        } catch (IOException e) {
            log.error("Failed to read file: {}", e.getMessage(), e);
            throw new BusinessException("fail to read file");
        }
        return result;
    }


    private static final int CHUNK_SIZE = 1024 * 1024;


    public static Map<String, Object> saveFile(String fileName, String fileContent, String fileType) throws IOException {
        if (fileName == null || fileContent == null) {
            throw new IllegalArgumentException("File name and content must not be empty");
        }

        Path filePath = Paths.get(fileName);
        ensureParentDirectoryExists(filePath);

        Path tempPath = Files.createTempFile("save", ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
            for (int i = 0; i < fileContent.length(); i += CHUNK_SIZE) {
                int end = Math.min(fileContent.length(), i + CHUNK_SIZE);
                writer.write(fileContent.substring(i, end));
            }
        }
        Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);

        Map<String, Object> result = new HashMap<>();
        result.put("path", filePath.toAbsolutePath().toString());
        result.put("size", Files.size(filePath));
        return result;
    }

    public static String openNativeSaveFileChooser(JFrame parentJFrame, String title, String defaultFileName) {
        try {
            String normalizedDefaultFileName = defaultFileName == null || defaultFileName.isBlank() ? "untitled.sql" : defaultFileName;
            String normalizedTitle = title == null ? "" : title;
            if (OS.isMacintosh()) {
                String script = """
                        tell application "System Events"
                            activate
                            set filePath to choose file name with prompt "%s" default name "%s"
                            set filePath to POSIX path of filePath
                        end tell
                        return filePath
                        """.formatted(escapeAppleScriptText(normalizedTitle), escapeAppleScriptText(normalizedDefaultFileName));

                Process process = Runtime.getRuntime().exec(new String[]{"osascript", "-e", script});
                process.waitFor();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String path = reader.readLine();
                    return path != null ? path.trim() : null;
                }
            }

            AtomicReference<String> selectedPath = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                if (!normalizedTitle.isBlank()) {
                    chooser.setDialogTitle(normalizedTitle);
                }
                chooser.setSelectedFile(new File(normalizedDefaultFileName));
                if (chooser.showSaveDialog(parentJFrame) == JFileChooser.APPROVE_OPTION) {
                    selectedPath.set(chooser.getSelectedFile().getAbsolutePath());
                }
            });
            return selectedPath.get();
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
            Thread.currentThread().interrupt();
        } catch (IOException | InvocationTargetException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    private static String escapeAppleScriptText(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    public static Map<String, Object> updateFileContent(String filePath, String fileContent) throws IOException {
        return updateFileContent(filePath, fileContent, null, null);
    }


    public static Map<String, Object> updateFileContent(
            String filePath,
            String fileContent,
            Charset charset,
            Boolean bom
    ) throws IOException {
        if (filePath == null || fileContent == null) {
            throw new IllegalArgumentException("File path and content must not be empty");
        }

        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new FileNotFoundException("File not found: " + filePath);
        }

        LocalTextFileCodec.DecodedText existingFile = null;
        if (charset == null || bom == null) {
            existingFile = LocalTextFileCodec.read(path, charset);
        }
        Charset targetCharset = charset != null ? charset : Charset.forName(existingFile.charset());
        boolean includeBom = bom != null ? bom : existingFile.bom();
        LocalTextFileCodec.write(path, fileContent, targetCharset, includeBom);

        Map<String, Object> result = new HashMap<>();
        result.put("path", path.toAbsolutePath().toString());
        result.put("size", Files.size(path));
        return result;
    }


    public static Map<String, Object> openLocalFile(String path, Charset charsets) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("File path must not be empty");
        }

        Path filePath = Paths.get(path);
        if (!Files.exists(filePath)) {
            throw new FileNotFoundException("File not found: " + path);
        }
        LocalTextFileCodec.DecodedText decodedText = LocalTextFileCodec.read(filePath, charsets);

        Map<String, Object> result = new HashMap<>();
        result.put("path", filePath.toAbsolutePath().toString());
        result.put("content", decodedText.content());
        result.put("charset", decodedText.charset());
        result.put("bom", decodedText.bom());
        result.put("size", Files.size(filePath));
        return result;
    }


    private static void ensureParentDirectoryExists(Path filePath) throws IOException {
        Path parent = filePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }


    public static Pair<String, String> openNativeFileChooser(JFrame parentJFrame, String baseDir, String extension, long maxSizeMB) {
        return NativeFileChooser.openFileDialog(parentJFrame, baseDir, maxSizeMB, extension);
    }


    public static String openNativeDirChooser(JFrame parentJFrame, String title) {
        try {
            if (OS.isMacintosh()) {
                String script = """
                        tell application "System Events"
                            activate
                            set folderPath to choose folder
                            set folderPath to POSIX path of folderPath
                        end tell
                        return folderPath
                        """;

                Process process = Runtime.getRuntime().exec(new String[]{"osascript", "-e", script});
                process.waitFor();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String path = reader.readLine();
                    return path != null ? path.trim() : null;
                }
            } else {
                AtomicReference<String> selectedPath = new AtomicReference<>(System.getProperty("user.dir"));
                SwingUtilities.invokeAndWait(() -> {
                    selectedPath.set(fallbackToJavaChooser(parentJFrame, title));
                });
                return selectedPath.get();
            }
        } catch (IOException | InterruptedException | InvocationTargetException e) {
            log.error(e.getMessage(), e);
            Thread.currentThread().interrupt();
        }
        return System.getProperty("user.home");
    }

    private static String fallbackToJavaChooser(JFrame parentJFrame, String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        String userHome = System.getProperty("user.home");
        File downloadsDir = new File(userHome, "Downloads");
        chooser.setCurrentDirectory(downloadsDir.isDirectory() ? downloadsDir : new File(userHome));

        if (chooser.showOpenDialog(parentJFrame) == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    public static String getCurrentJarPath() {
        URL location = GetJarPathHandler.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation();

        String jarPath = normalizeCodeSourcePath(location);
        File jarFile = new File(jarPath);
        File currentPath;
        if (jarFile.isDirectory()) {
            currentPath = jarFile.getParentFile().getParentFile();
        } else {
            currentPath = jarFile.getParentFile();
        }
        return resolvePackagedAppRoot(currentPath).getAbsolutePath();
    }

    private static File resolvePackagedAppRoot(File currentPath) {
        if (currentPath == null) {
            return new File(".").getAbsoluteFile();
        }

        File absolutePath = currentPath.getAbsoluteFile();
        if (!"lib".equals(absolutePath.getName())) {
            return absolutePath;
        }

        File parent = absolutePath.getParentFile();
        if (parent != null && isPackagedAppDirectory(parent)) {
            return parent;
        }
        return absolutePath;
    }

    private static boolean isPackagedAppDirectory(File directory) {
        return new File(directory, "dist").isDirectory()
                || new File(directory, "Frameworks").isDirectory()
                || new File(directory, ".jpackage.xml").isFile()
                || new File(directory, "local_version.json").isFile();
    }

    static String normalizeCodeSourcePath(URL location) {
        String jarPath = URLDecoder.decode(location.toExternalForm(), StandardCharsets.UTF_8);

        while (jarPath.startsWith("jar:") || jarPath.startsWith("war:")
                || jarPath.startsWith("nested:") || jarPath.startsWith("file:")) {
            int separatorIndex = jarPath.indexOf(':');
            jarPath = jarPath.substring(separatorIndex + 1);
        }

        if (jarPath.contains("!")) {
            jarPath = jarPath.substring(0, jarPath.indexOf('!'));
        }
        if (jarPath.matches("^/[A-Za-z]:/.*")) {
            jarPath = jarPath.substring(1);
        }

        return jarPath;
    }

    public static void openLog(JFrame frame) {
        Path actualLogFilePath = null;
        String foundByMethod = "";

        String logPathFromSystemProp = System.getProperty("log.path");
        if (logPathFromSystemProp != null && !logPathFromSystemProp.isEmpty()) {
            Path tempPath = Paths.get(logPathFromSystemProp);
            if (Files.exists(tempPath) && Files.isRegularFile(tempPath)) {
                actualLogFilePath = tempPath;
                foundByMethod = "System Property 'log.path'";
            }
        }

        if (actualLogFilePath == null) {
            String customLogPathProp = System.getProperty("chat2db.log.path");
            if (customLogPathProp != null && !customLogPathProp.isEmpty()) {
                Path tempPath = Paths.get(customLogPathProp);
                if (Files.exists(tempPath) && Files.isRegularFile(tempPath)) {
                    actualLogFilePath = tempPath;
                    foundByMethod = "System Property 'chat2db.log.path'";
                }
            }
        }
        if (actualLogFilePath == null) {
            String userHome = System.getProperty("user.home");
            ArrayList<Path> potentialPaths = Lists.newArrayList(
                    Paths.get(ConfigUtils.getBasePath(), "chat2db-community", "logs", "application.log"),
                    Paths.get(userHome, "chat2db-community", "logs", "application.log"),
                    Paths.get(System.getProperty("user.dir"), "logs", "application.log")
            );

            for (Path p : potentialPaths) {
                if (Files.exists(p) && Files.isRegularFile(p)) {
                    actualLogFilePath = p;
                    foundByMethod = "Manually constructed path pattern";
                    break;
                }
            }
        }

        if (actualLogFilePath != null) {
            System.out.println("Log file found (" + foundByMethod + "): " + actualLogFilePath.toAbsolutePath());
            String osName = System.getProperty("os.name").toLowerCase();

            if (osName.contains("mac")) {
                try {
                    String absoluteLogPath = actualLogFilePath.toAbsolutePath().toString();
                    String[] command = {"open", "-a", "Console", absoluteLogPath};
                    Runtime.getRuntime().exec(command);
                } catch (IOException ex) {
                    log.error("Error opening log in Console.app: {}. Falling back to default editor.", ex.getMessage());
                    openWithDefaultEditor(frame, actualLogFilePath);
                }
            } else {
                System.out.println("OS is not macOS (" + osName + "), opening with default system editor.");
                openWithDefaultEditor(frame, actualLogFilePath);
            }
        } else {
            String msg = "Unable to locate a valid log file (application.log)";
            log.error(msg);
            JOptionPane.showMessageDialog(frame, msg, "Log File Error", JOptionPane.ERROR_MESSAGE);
        }
    }


    private static void openWithDefaultEditor(JFrame parentFrame, Path filePath) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            try {
                Desktop.getDesktop().open(filePath.toFile());
                System.out.println("Opened log with default system editor: " + filePath);
            } catch (IOException ex) {
                System.err.println("Error opening log with default editor: " + ex.getMessage());
                JOptionPane.showMessageDialog(parentFrame, "Unable to open the log file with the system's default editor.\nError: " + ex.getMessage(), "Open Log Error", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            System.err.println("Desktop.Action.OPEN is not supported on this platform.");
            JOptionPane.showMessageDialog(parentFrame, "The current platform does not support opening files automatically.", "Open Log Error", JOptionPane.ERROR_MESSAGE);
        }
    }


    private static final String LANGUAGE_KEY = "user.language.preference";

    public static void saveLanguagePreference(String language) {
        Preferences.userRoot().put(LANGUAGE_KEY, language);
    }

    public static String getLanguagePreference() {
        return Preferences.userRoot().get(LANGUAGE_KEY,
                getPreferredLanguage());
    }


    public static String getSystemLanguage() {
        return Locale.getDefault().toLanguageTag();
    }


    public static String getPreferredLanguage() {
        String userLanguage = System.getProperty("user.language");
        String userCountry = System.getProperty("user.country");

        if (userLanguage != null && !userLanguage.isEmpty()) {
            return userCountry != null && !userCountry.isEmpty()
                    ? userLanguage + "-" + userCountry
                    : userLanguage;
        }
        return getSystemLanguage();
    }

    public static Map<String, Integer> getScreenInfo(JFrame frame) {
        if (OS.isMacintosh()) {
            GraphicsConfiguration gc = frame.getGraphicsConfiguration();
            GraphicsDevice gd = gc.getDevice();
            DisplayMode dm = gd.getDisplayMode();
            return Map.of("width", dm.getWidth(), "height", dm.getHeight());
        }
        Rectangle rectangle = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        double screenWidth = rectangle.getWidth();
        double screenHeight = rectangle.getHeight();
        return Map.of("width", (int) screenWidth, "height", (int) screenHeight);
    }
}

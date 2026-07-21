package ai.chat2db.community.jcef.frame;
import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.tools.config.SystemSettingConstant;
import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import ai.chat2db.community.jcef.enums.AppThemeEnum;
import ai.chat2db.community.jcef.enums.OSTypeEnum;
import ai.chat2db.community.jcef.enums.ThemeEnum;
import ai.chat2db.community.jcef.event.manager.FileOpenEventManager;
import ai.chat2db.community.jcef.handler.biz.IJcefActionHandler;
import ai.chat2db.community.jcef.handler.keyboard.KeyboardHandler;
import ai.chat2db.community.jcef.handler.mouse.CursorHandler;
import ai.chat2db.community.jcef.listener.FileManagerService;
import ai.chat2db.community.jcef.menus.Chat2DBMenuBar;
import ai.chat2db.community.jcef.utils.*;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.console.ConsoleCodec;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.console.bridge.IJcefServerBridge;
import ai.chat2db.community.tools.console.bridge.JcefServerBridgeRegistry;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dtflys.forest.exceptions.ForestNetworkException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.jetbrains.cef.JCefAppConfig;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.OS;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.browser.CefRendering;
import org.cef.callback.CefCommandLine;
import org.cef.callback.CefDragData;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefAppHandlerAdapter;
import org.cef.handler.CefDragHandler;
import org.cef.handler.CefFocusHandler;
import org.cef.handler.CefFocusHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.network.CefRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import javax.swing.*;
import javax.swing.plaf.BorderUIResource;
import java.awt.*;
import java.awt.desktop.AppReopenedListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
public class MainJFrame extends JFrame {
    private static volatile MainJFrame instance = null;
    private final JcefContext JCEF_CONTEXT = JcefContext.getInstance();
    private static final Logger log = LoggerFactory.getLogger(MainJFrame.class);
    private static final String CHAT2DB_IPC_RESPONSE_SERVICE_STATUS_SUCCESS =
            ConsoleCodec.CHAT2DB_IPC_RESPONSE_SERVICE_STATUS_SUCCESS;
    private static final String MAC_OS_14_1_VERSION_PREFIX = "14.1";
    private JSplitPane splitPane;
    private DevToolsPanel devToolsPanel;
    private boolean isDevToolsVisible = false;
    private CefApp cefApp_;
    private CefClient client_;
    private CefBrowser browser_;
    private Component browserUI_;
    private JCefAppConfig jcefAppConfig_;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Pair<String, String>, IJcefActionHandler> actionHandlers = new HashMap<>();
    private static final String appName;
    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(100,
            200,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>()
    );
    private void ensureBrowserFocusIfNeeded() {
        if (browser_ == null || browserUI_ == null) {
            return;
        }
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focusOwner != null && (focusOwner == browserUI_ || SwingUtilities.isDescendingFrom(focusOwner, browserUI_))) {
            return;
        }
        browser_.setFocus(true);
        browserUI_.requestFocusInWindow();
    }
    public static MainJFrame getInstance() {
        if (instance == null) {
            synchronized (MainJFrame.class) {
                if (instance == null) {
                    instance = new MainJFrame();
                }
            }
        }
        return instance;
    }
    static {
        appName = DesktopProductTitle.resolve(ConfigUtils.isCommunity(), ConfigUtils.isLocalEdition());
        if (!OS.isMacintosh()) {
            JFrame.setDefaultLookAndFeelDecorated(true);
            JDialog.setDefaultLookAndFeelDecorated(true);
            UIManager.put("TitlePane.showIcon", false);
            UIManager.put("TitlePane.titleMargins", new Insets(5, 0, 0, 0));
            UIManager.put("TitlePane.noIconLeftGap", 0);
            try {
                String appearance = (String) SystemSettingsUtil.getProperty(SystemSettingConstant.SYSTEM_APPEARANCE);
                if (StringUtils.isNotBlank(appearance) && Objects.nonNull(AppThemeEnum.fromString(appearance))) {
                    AppThemeEnum appThemeEnum = AppThemeEnum.fromString(appearance);
                    if (AppThemeEnum.DARK.equals(appThemeEnum)) {
                        UIManager.setLookAndFeel(new FlatDarkLaf());
                    } else if (AppThemeEnum.DARK_DIMMED.equals(appThemeEnum)) {
                        UIManager.setLookAndFeel(new FlatMacDarkLaf());
                    } else {
                        UIManager.setLookAndFeel(new FlatMacLightLaf());
                    }
                } else {
                    UIManager.setLookAndFeel(new FlatMacDarkLaf());
                }
            } catch (UnsupportedLookAndFeelException e) {
                throw new RuntimeException(e);
            }
        }
    }
    private static void handleNewUri(String uriString) {
        SwingUtilities.invokeLater(() -> {
            if (JcefContext.getInstance().getFrame_() != null) {
                if (uriString != null) {
                    JcefContext.getInstance().getFrame_().processUri(UriUtil.processInput(uriString));
                    JcefContext.getInstance().getFrame_().toFront();
                    JcefContext.getInstance().getFrame_().requestFocus();
                }
            } else {
                log.error("Error: application is not initialized; cannot process URI: {}", uriString);
            }
        });
    }
    public void start(String[] args) {
        if (!OS.isMacintosh() && !SingleInstanceUtil.registerInstance(args, MainJFrame::handleNewUri)) {
            System.exit(0);
        }
        UrlProtocolRegistrarUtil.register();
        initPreProcessor();
        initializeCefApp(args);
    }
    private void setupMacFullScreenListenerViaReflection(JFrame frame) {
        if (!OS.isMacintosh()) {
            return;
        }
        try {
            Class<?> fullScreenUtilitiesClass = Class.forName("com.apple.eawt.FullScreenUtilities");
            Class<?> fullScreenListenerClass = Class.forName("com.apple.eawt.FullScreenListener");
            Method setWindowCanFullScreenMethod = fullScreenUtilitiesClass.getMethod("setWindowCanFullScreen", Window.class, boolean.class);
            Method addFullScreenListenerToMethod = fullScreenUtilitiesClass.getMethod("addFullScreenListenerTo", Window.class, fullScreenListenerClass);
            InvocationHandler handler = (proxy, method, args) -> {
                String methodName = method.getName();
                if ("windowEnteringFullScreen".equals(methodName)) {
                    Integer screenWidth = JcefContext.getInstance().getScreenWidth();
                    Integer screenHeight = JcefContext.getInstance().getScreenHeight();
                    SwingUtilities.invokeLater(() -> {
                        frame.setSize(new Dimension(screenWidth, screenHeight));
                        frame.revalidate();
                        frame.repaint();
                    });
                }
                return null;
            };
            Object listenerProxy = Proxy.newProxyInstance(
                    CefApp.class.getClassLoader(),
                    new Class<?>[]{fullScreenListenerClass},
                    handler
            );
            setWindowCanFullScreenMethod.invoke(null, frame, true);
            addFullScreenListenerToMethod.invoke(null, frame, listenerProxy);
        } catch (Exception e) {
            log.error("Failed to set up macOS full screen listener via reflection. This is expected on non-Apple JDKs or newer macOS versions where this API is deprecated.");
        }
    }
    public void processUri(URI uri) {
        String query = uri.getQuery();
        if (query != null && query.startsWith("token=")) {
            if (ConfigUtils.isCommunity()) {
                log.info("Ignore hosted login callback in community mode.");
                return;
            }
            String combinedToken = query.substring("token=".length());
            String encode = URLEncoder.encode(combinedToken, StandardCharsets.UTF_8);
            String decodedToken = URLDecoder.decode(encode, StandardCharsets.UTF_8);
            if (JcefServerBridgeRegistry.getBridge().loginToken(decodedToken)) {
                onLoginSuccess();
            }
        } else if (query != null && query.startsWith("console")) {
            toggleDevTools();
        } else {
            Path path = Paths.get(uri);
            if (Files.exists(path)) {
                String filePath = path.toAbsolutePath().toString();
                FileOpenEventManager.stashFileOpenEvent(filePath);
            } else {
                log.error("Invalid token format; unable to parse.");
            }
        }
    }
    private void onLoginSuccess() {
        ConsoleResult consoleResult = new ConsoleResult();
        consoleResult.setMessage(Map.of("data", true));
        consoleResult.setActionType(ActionTypeEnum.OSS_LOGIN.getName());
        String result = JSON.toJSONString(consoleResult);
        CallJsFunctionUtil.callHandleJavaMessage(JcefContext.getInstance().getBrowser_(), result);
    }
    private void initPreProcessor() {
        setupSystemProperties();
        setupDesktopIntegration();
        Image appIcon = buildAppLogoImage();
        this.setTitle(appName);
        if (OS.isMacintosh()) {
            setupMacSpecifics(appIcon);
        } else {
            setupGenericPlatformSpecifics(appIcon);
        }
    }
    private void setupSystemProperties() {
        System.setProperty("ide.browser.jcef.osr.enabled", "false");
        System.setProperty("sun.java2d.opengl", "true");
        if (OS.isMacintosh()) {
            System.setProperty("apple.laf.useScreenMenuBar", String.valueOf(!isMacOs141()));
        }
    }
    private boolean isMacOs141() {
        String osVersion = System.getProperty("os.version", "");
        return MAC_OS_14_1_VERSION_PREFIX.equals(osVersion) || osVersion.startsWith(MAC_OS_14_1_VERSION_PREFIX + ".");
    }
    private void setupDesktopIntegration() {
        if (!Desktop.isDesktopSupported()) {
            log.warn("Desktop API is not supported on this platform.");
            return;
        }
        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            desktop.setQuitHandler((e, response) -> {
                log.info("Quit handler triggered. Preparing for graceful shutdown.");
                SystemSettingsUtil.saveWindowsInfo();
                JcefContext.getInstance().getFrame_().dispose();
                CefApp.getInstance().dispose();
                Timer timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        log.info("CefApp is shutting down... {}", Thread.currentThread().getName());
                        response.performQuit();
                    }
                }, 3000);
            });
        }
        if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
            desktop.setOpenURIHandler(e -> {
                log.info("Open URI event received for: {}", e.getURI());
                SwingUtilities.invokeLater(() -> {
                    processUri(e.getURI());
                    toFront();
                    requestFocus();
                });
            });
        }
        if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
            desktop.setOpenFileHandler(e -> {
                List<File> files = e.getFiles();
                if (files != null && !files.isEmpty()) {
                    String filePath = files.get(0).getAbsolutePath();
                    log.info("Open file event received for: {}", filePath);
                    FileOpenEventManager.stashFileOpenEvent(filePath);
                }
            });
        }
        if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            desktop.addAppEventListener((AppReopenedListener) e -> {
                log.info("Application reopened from Dock (or similar macOS event).");
                final JFrame mainFrame = JcefContext.getInstance().getFrame_();
                SwingUtilities.invokeLater(() -> {
                    if (mainFrame != null) {
                        mainFrame.setVisible(true);
                        mainFrame.toFront();
                        mainFrame.requestFocus();
                    }
                });
            });
        }
    }
    private void setupMacSpecifics(Image appIcon) {
        getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
        getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path frameworksPath = javaHome.resolve("../Frameworks").normalize();
        if (!Files.exists(frameworksPath)) {
            log.warn("Standard JCEF framework path not found. Setting alternative paths.");
            Path basePath = resolveMacJcefAppPath();
            String alternativeFrameworkDir = basePath.resolve("Frameworks/Chromium Embedded Framework.framework").toString();
            String alternativeHelperAppDir = basePath.resolve("Frameworks/jcef Helper.app").toString();
            log.info("Resolved macOS JCEF fallback basePath: {}", basePath);
            log.info("Resolved macOS JCEF fallback java.home: {}", javaHome);
            System.setProperty("ALT_CEF_FRAMEWORK_DIR", alternativeFrameworkDir);
            System.setProperty("ALT_CEF_HELPER_APP_DIR", alternativeHelperAppDir);
            log.info("ALT_CEF_FRAMEWORK_DIR: {}", System.getProperty("ALT_CEF_FRAMEWORK_DIR"));
            log.info("ALT_CEF_HELPER_APP_DIR: {}", System.getProperty("ALT_CEF_HELPER_APP_DIR"));
        }
        setupMacFullScreenListenerViaReflection(this);
        if (Taskbar.isTaskbarSupported()) {
            Taskbar.getTaskbar().setIconImage(appIcon);
        }
    }
    private Path resolveMacJcefAppPath() {
        Path currentJarPath = Paths.get(OSOperateUtil.getCurrentJarPath()).toAbsolutePath().normalize();
        if (Files.exists(currentJarPath.resolve("Frameworks/Chromium Embedded Framework.framework"))) {
            return currentJarPath;
        }
        Path parent = currentJarPath.getParent();
        if (parent != null && Files.exists(parent.resolve("Frameworks/Chromium Embedded Framework.framework"))) {
            return parent;
        }
        return currentJarPath;
    }
    private void setupGenericPlatformSpecifics(Image appIcon) {
        this.setIconImage(appIcon);
        applyTheme(ThemeEnum.DARK);
    }
    private static Image buildAppLogoImage() {
        ResourceLoader resourceLoader = new DefaultResourceLoader();
        Resource resource;
        if (ConfigUtils.isOffline()) {
            resource = resourceLoader.getResource("classpath:logo_local.png");
        }else {
            resource = resourceLoader.getResource("classpath:logo_pro.png");
        }
        URL imageUrl;
        try {
            imageUrl = resource.getURL();
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new BusinessException(e.getMessage());
        }
        log.info("Image URL: {}", imageUrl);
        return new ImageIcon(imageUrl).getImage();
    }
    public void applyTheme(ThemeEnum theme) {
        log.info("Applying {} theme to menu UI.", theme);
        applyCommonMenuFont();
        if (theme == ThemeEnum.DARK) {
            initDarkModeMenuUI();
        } else {
            initLightModeMenuUI();
        }
        SwingUtilities.updateComponentTreeUI(this);
    }
    private void applyCommonMenuFont() {
        Font defaultFont = UIManager.getFont("Menu.font");
        if (defaultFont == null) {
            defaultFont = new Font("SansSerif", Font.PLAIN, 12);
        }
        Font newMenuFont = defaultFont.deriveFont(Font.BOLD, 13f);
        UIManager.put("TitlePane.font", newMenuFont);
        UIManager.put("Menu.font", newMenuFont);
        UIManager.put("MenuItem.font", newMenuFont);
        UIManager.put("MenuBar.font", newMenuFont);
    }
    public void initDarkModeMenuUI() {
        Color menuBarBackground = new Color(60, 63, 65);
        Color menuItemForeground = new Color(230, 230, 230);
        Color menuItemSelectedBackground = new Color(75, 110, 175);
        Color menuItemSelectedForeground = Color.WHITE;
        Color separatorColor = new Color(85, 85, 85);
        Color acceleratorForeground = new Color(153, 153, 153);
        UIManager.put("MenuBar.background", menuBarBackground);
        UIManager.put("MenuBar.borderColor", menuBarBackground);
        UIManager.put("MenuBar.border", new BorderUIResource.EmptyBorderUIResource(4, 8, 4, 8));
        UIManager.put("MenuBar.itemMargins", new Insets(0, 6, 0, 6));
        UIManager.put("Menu.foreground", menuItemForeground);
        UIManager.put("Menu.background", menuBarBackground);
        UIManager.put("Menu.selectionBackground", menuItemSelectedBackground);
        UIManager.put("Menu.selectionForeground", menuItemSelectedForeground);
        UIManager.put("Menu.borderPainted", false);
        UIManager.put("MenuItem.foreground", menuItemForeground);
        UIManager.put("MenuItem.background", menuBarBackground);
        UIManager.put("MenuItem.selectionBackground", menuItemSelectedBackground);
        UIManager.put("MenuItem.selectionForeground", menuItemSelectedForeground);
        UIManager.put("MenuItem.acceleratorForeground", acceleratorForeground);
        UIManager.put("MenuItem.acceleratorSelectionForeground", menuItemSelectedForeground);
        UIManager.put("MenuItem.iconTextGap", -15);
        UIManager.put("PopupMenu.background", menuBarBackground);
        UIManager.put("PopupMenu.borderColor", separatorColor);
        UIManager.put("PopupMenuSeparator.foreground", separatorColor);
    }
    public void initLightModeMenuUI() {
        Color menuBarBackground = new Color(242, 242, 242);
        Color menuItemForeground = new Color(51, 51, 51);
        Color menuItemSelectedBackground = new Color(0, 120, 215);
        Color menuItemSelectedForeground = Color.WHITE;
        Color separatorColor = new Color(221, 221, 221);
        Color acceleratorForeground = new Color(117, 117, 117);
        UIManager.put("MenuBar.background", menuBarBackground);
        UIManager.put("MenuBar.borderColor", separatorColor);
        UIManager.put("MenuBar.border", new BorderUIResource.EmptyBorderUIResource(4, 8, 4, 8));
        UIManager.put("MenuBar.itemMargins", new Insets(0, 6, 0, 6));
        UIManager.put("Menu.foreground", menuItemForeground);
        UIManager.put("Menu.background", menuBarBackground);
        UIManager.put("Menu.selectionBackground", menuItemSelectedBackground);
        UIManager.put("Menu.selectionForeground", menuItemSelectedForeground);
        UIManager.put("Menu.borderPainted", true);
        UIManager.put("MenuItem.foreground", menuItemForeground);
        UIManager.put("MenuItem.background", menuBarBackground);
        UIManager.put("MenuItem.selectionBackground", menuItemSelectedBackground);
        UIManager.put("MenuItem.selectionForeground", menuItemSelectedForeground);
        UIManager.put("MenuItem.acceleratorForeground", acceleratorForeground);
        UIManager.put("MenuItem.acceleratorSelectionForeground", menuItemSelectedForeground);
        UIManager.put("MenuItem.iconTextGap", -15);
        UIManager.put("PopupMenu.background", menuBarBackground);
        UIManager.put("PopupMenu.borderColor", separatorColor);
        UIManager.put("PopupMenuSeparator.foreground", separatorColor);
    }
    private void initPostProcessor() {
        JCEF_CONTEXT.buildJcefContext(
                this,
                this.browser_,
                this.client_,
                this.cefApp_,
                this.splitPane,
                this.devToolsPanel,
                this.browserUI_,
                this.jcefAppConfig_
        );
        this.client_.addDisplayHandler(new CursorHandler());
        this.client_.addKeyboardHandler(new KeyboardHandler());
        this.client_.addFocusHandler(new CefFocusHandlerAdapter() {
            @Override
            public void onGotFocus(CefBrowser browser) {
                ensureBrowserFocusIfNeeded();
            }
            @Override
            public boolean onSetFocus(CefBrowser browser, CefFocusHandler.FocusSource source) {
                return false;
            }
        });
        this.client_.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
                if (frame.isMain()) {
                    String languagePreference = OSOperateUtil.getLanguagePreference();
                    OSTypeEnum osType = JcefContext.getInstance().getOsType();
                    browser.executeJavaScript(String.format("window.navigator.app_language = '%s';", languagePreference), browser.getURL(), 0);
                    browser.executeJavaScript(String.format("window.navigator.os_type = '%s';", osType), browser.getURL(), 0);
                }
            }
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            }
        });
        this.client_.addDragHandler(new CefDragHandler() {
            @Override
            public boolean onDragEnter(CefBrowser browser, CefDragData dragData, int mask) {
                if (dragData.isFile()) {
                    Vector<String> filePaths = new Vector<>();
                    dragData.getFileNames(filePaths);
                    for (String filePath : filePaths) {
                        File file = new File(filePath);
                        String absolutePath = file.getAbsolutePath();
                        log.info("Dropped file: {}", absolutePath);
                    }
                    return true;
                }
                return false;
            }
        });
        Chat2DBMenuBar.setupMenuBar();
        new FileManagerService();
        ConsoleResult consoleResult = new ConsoleResult();
        consoleResult.setActionType(ActionTypeEnum.STARTUP_COMPLETE.getName());
        consoleResult.setMessage(Map.of("data", CHAT2DB_IPC_RESPONSE_SERVICE_STATUS_SUCCESS));
        CallJsFunctionUtil.callHandleJavaMessage(this.browser_, JSON.toJSONString(consoleResult));
    }
    private void initializeCefApp(String[] args) {
        log.info("1. Starting JCEF application initialization... ");
        final JCefAppConfig jCefAppConfig = JCefAppConfig.getInstance();
        final CefSettings cefSettings = jCefAppConfig.getCefSettings();
        cefSettings.windowless_rendering_enabled = false;
        cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_DEFAULT;
        File chat2dbCache = Paths.get(SystemSettingsUtil.getCachePath()).toFile();
        if (!chat2dbCache.exists()) {
            boolean cache = chat2dbCache.mkdirs();
            log.info("cache dir create: {}", cache);
        }
        cefSettings.cache_path = chat2dbCache.getAbsolutePath();
        CefApp.addAppHandler(new CefAppHandlerAdapter(args) {
            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                if (state == CefApp.CefAppState.TERMINATED) {
                    log.info("CEF app terminated; the application will exit.");
                    System.exit(0);
                }
            }
            @Override
            public void onContextInitialized() {
                super.onContextInitialized();
                SwingUtilities.invokeLater(()-> {
                    log.info("CEF App ContextInitialized");
                    initializeActionHandlers();
                    initializeClientAndRouter();
                    initializeBrowserAndUI();
                    initializeFrame();
                    initPostProcessor();
                });
            }
            @Override
            public void onBeforeCommandLineProcessing(String processType, CefCommandLine commandLine) {
                super.onBeforeCommandLineProcessing(processType, commandLine);
                NetworkProxyUtil.applyToCefCommandLine(commandLine);
                if (OS.isWindows()) {
                    commandLine.appendSwitch("disable-gpu");
                    commandLine.appendSwitch("disable-gpu-compositing");
                }
                if (OS.isLinux()) {
                    GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                    GraphicsDevice gd = ge.getDefaultScreenDevice();
                    GraphicsConfiguration gc = gd.getDefaultConfiguration();
                    final double scaleFactor = gc != null ? gc.getDefaultTransform().getScaleX() : (double) 1.0F;
                    if (scaleFactor > 1.0) {
                        commandLine.appendSwitchWithValue("force-device-scale-factor", String.valueOf(scaleFactor));
                    }
                }
            }
        });
        CefApp.startup(jCefAppConfig.getAppArgs());
        this.cefApp_ = CefApp.getInstance(jCefAppConfig.getAppArgs(), cefSettings);
        this.jcefAppConfig_ = jCefAppConfig;
        log.info(">>> CefAppBuilder.build() completed. CefApp state: {}", (this.cefApp_ != null ? CefApp.getState() : "cefApp_ is null"));
        if (this.cefApp_ == null) {
            String errorMsg = "CefApp initialization failed (builder.build() returned null or initialization did not complete)";
            log.error(errorMsg);
            System.exit(-1);
        }
        log.info("1. JCEF application initialization completed.");
    }
    private void initializeActionHandlers() {
        log.info("2. Starting action handler initialization (automatic scan)...");
        String scanPackage = IJcefActionHandler.class.getPackage().getName();
        try (ScanResult scanResult = new ClassGraph()
                .enableAllInfo()
                .acceptPackages(scanPackage)
                .scan()) {
            ClassInfoList handlerClassInfoList = scanResult
                    .getClassesImplementing(IJcefActionHandler.class.getName())
                    .filter(classInfo -> classInfo.hasAnnotation(JcefAction.class.getName()));
            for (ClassInfo classInfo : handlerClassInfoList) {
                try {
                    Class<?> handlerClass = classInfo.loadClass();
                    JcefAction annotation = handlerClass.getAnnotation(JcefAction.class);
                    String path = annotation.value();
                    String method = annotation.method();
                    Pair<String, String> actionKey = Pair.of(path, method.toLowerCase());
                    IJcefActionHandler handlerInstance;
                    try {
                        Constructor<?> constructor = handlerClass.getDeclaredConstructor(ObjectMapper.class);
                        handlerInstance = (IJcefActionHandler) constructor.newInstance(objectMapper);
                    } catch (NoSuchMethodException e) {
                        try {
                            Constructor<?> constructor = handlerClass.getDeclaredConstructor();
                            handlerInstance = (IJcefActionHandler) constructor.newInstance();
                        } catch (NoSuchMethodException e2) {
                            log.error("Handler class {} does not have a public no-argument constructor or a constructor accepting ObjectMapper.", handlerClass.getName(), e2);
                            continue;
                        }
                    }
                    if (actionHandlers.containsKey(actionKey)) {
                        log.warn("Warning: action '{}' is already registered and will be overridden by {} (previous handler: {})",
                                path, handlerClass.getName(), actionHandlers.get(actionKey).getClass().getName());
                    }
                    actionHandlers.put(actionKey, handlerInstance);
                    log.info("Auto-registering action handler: '{}', '{}' -> {}", path, method, handlerClass.getName());
                } catch (Exception e) {
                    log.error("Unable to instantiate or register handler: {}", classInfo.getName(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error while scanning the classpath for action handlers:", e);
        }
        log.info("2. Action handler initialization completed; {} handlers registered automatically.", actionHandlers.size());
    }
    private void initializeClientAndRouter() {
        log.info("3. Starting CefClient and MessageRouter initialization...");
        this.client_ = this.cefApp_.createClient();
        CefMessageRouter messageRouter = CefMessageRouter.create(
                new CefMessageRouter.CefMessageRouterConfig("javaQuery", "javaCancelQuery")
        );
        messageRouter.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String data,
                                   boolean persistent, CefQueryCallback callback) {
                executor.submit(() -> {
                    String action = "unKnow Action";
                    ConsoleMessage wsMessage = new ConsoleMessage();
                    ConsoleResult wsResult;
                    try {
                        wsMessage = JSONObject.parseObject(data, ConsoleMessage.class);
                        action = wsMessage.getRequestUrl();
                        log.info("Java received a query from JS, request URL: {}, UUID: {}", wsMessage.getRequestUrl(), wsMessage.getUuid());
                        wsResult = ConsoleResult.builder()
                                .uuid(wsMessage.getUuid())
                                .actionType(wsMessage.getActionType())
                                .requestUrl(wsMessage.getRequestUrl())
                                .method(wsMessage.getMethod())
                                .build();
                        IJcefServerBridge bridge = JcefServerBridgeRegistry.getBridge();
                        bridge.setHeaders(wsMessage);
                        IJcefActionHandler handler = actionHandlers.get(Pair.of(action, wsMessage.getMethod().toLowerCase()));
                        if (handler != null) {
                            handler.handle(wsMessage, wsResult, callback);
                        } else {
                            while (!bridge.isReady()) {
                                Thread.sleep(20);
                            }
                            wsResult = bridge.doController(wsMessage);
                            if (wsResult != null) {
                                ResponseBuilder.buildSuccess(wsResult, callback);
                            } else {
                                ResponseBuilder.buildSuccess(
                                        ConsoleResult.builder()
                                                .uuid(wsMessage.getUuid())
                                                .actionType(wsMessage.getActionType())
                                                .requestUrl(wsMessage.getRequestUrl())
                                                .method(wsMessage.getMethod())
                                                .message(Map.of("success", true))
                                                .build(),
                                        callback
                                );
                            }
                        }
                    } catch (ForestNetworkException ex) {
                        handleNetworkException(ex, wsMessage, callback, action);
                    } catch (Exception e) {
                        handleGenericException(e, wsMessage, callback, action);
                    }
                });
                return true;
            }
            @Override
            public void onQueryCanceled(CefBrowser browser, CefFrame frame, long queryId) {
                log.info("JS query canceled: {}", queryId);
            }
        }, true);
        this.client_.addMessageRouter(messageRouter);
        log.info("3. CefClient and MessageRouter initialization completed.");
    }
    private void handleNetworkException(ForestNetworkException ex,
                                        ConsoleMessage wsMessage,
                                        CefQueryCallback callback,
                                        String action) {
        log.error("Network request exception [action: {}]: {}", action, ex.getMessage(), ex);
        ResponseBuilder.buildSuccess(
                JcefServerBridgeRegistry.getBridge().error(ex, wsMessage),
                callback
        );
    }
    private void handleGenericException(Exception e,
                                        ConsoleMessage wsMessage,
                                        CefQueryCallback callback,
                                        String action) {
        log.error("Error processing request [action: {}]: {}", action, e.getMessage(), e);
        ResponseBuilder.buildSuccess(
                JcefServerBridgeRegistry.getBridge().error(e, wsMessage),
                callback
        );
    }
    private void initializeBrowserAndUI() {
        log.info("4. Starting CefBrowser and UI component creation...");
        String currentJarPath = OSOperateUtil.getCurrentJarPath();
        String indexHtmlFile;
        try {
            Path indexHtmlPath = Paths.get(currentJarPath, "dist", "index.html").toAbsolutePath().normalize();
            if (!Files.isRegularFile(indexHtmlPath)) {
                log.error("JCEF index file not found: {}", indexHtmlPath);
                throw new BusinessException("Failed to load frontend files");
            }
            indexHtmlFile = indexHtmlPath.toUri().toURL().toString();
            log.info("Resolved JCEF index file: {}", indexHtmlFile);
        } catch (MalformedURLException e) {
            log.error(e.getMessage(), e);
            throw new BusinessException("Failed to load frontend files");
        }
        this.browser_ = this.client_.createBrowser(indexHtmlFile, CefRendering.DEFAULT, false);
        this.browserUI_ = browser_.getUIComponent();
        this.browserUI_.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                browser_.sendMouseEvent(e);
            }
            @Override
            public void mousePressed(MouseEvent e) {
                ensureBrowserFocusIfNeeded();
            }
        });
        if (OS.isMacintosh()) {
            this.browserUI_.setBackground(MacThemeUtil.getThemeColor());
        } else {
            this.browserUI_.setBackground(ThemeUtil.getThemeColor());
        }
        this.devToolsPanel = new DevToolsPanel(browser_);
        this.devToolsPanel.setCloseListener(() -> {
            JcefContext.getInstance().getFrame_().toggleDevTools();
        });
        this.splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, browserUI_, devToolsPanel);
        this.splitPane.setBorder(null);
        this.splitPane.setDividerSize(0);
        this.splitPane.setResizeWeight(0.66);
        this.splitPane.setContinuousLayout(true);
        if (OS.isMacintosh()) {
            this.splitPane.setBackground(MacThemeUtil.getThemeColor());
        } else {
            this.splitPane.setBackground(ThemeUtil.getThemeColor());
        }
        this.splitPane.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (Math.abs(e.getX() - splitPane.getDividerLocation()) < 3) {
                    splitPane.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
                } else {
                    splitPane.setCursor(Cursor.getDefaultCursor());
                }
            }
        });
        log.info("4. CefBrowser and UI component creation completed.");
    }
    private void initializeFrame() {
        log.info("5. Starting JFrame initialization...");
        initAppWindowSize();
        if (OS.isMacintosh()) {
            this.setBackground(MacThemeUtil.getThemeColor());
        }else {
            this.setBackground(ThemeUtil.getThemeColor());
        }
        this.getContentPane().add(splitPane, BorderLayout.CENTER);
        this.setLocationRelativeTo(null);
        this.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (OS.isMacintosh()) {
                    log.info("macOS 'X' button clicked. Hiding window.");
                    setVisible(false);
                } else {
                    log.info("Non-macOS 'X' button clicked. Disposing window and exiting application.");
                    JFrame frameToClose = (JFrame) e.getSource();
                    OSOperateUtil.closeWindows(frameToClose);
                }
            }
        });
        this.addWindowFocusListener(new WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                SwingUtilities.invokeLater(MainJFrame.this::ensureBrowserFocusIfNeeded);
            }
            @Override
            public void windowLostFocus(WindowEvent e) {
            }
        });
        this.setVisible(true);
        log.info("5. JFrame initialization completed.");
    }
    private void initAppWindowSize() {
        Boolean isMaxWindow = (Boolean) SystemSettingsUtil.getProperty(SystemSettingConstant.IS_MAX_WINDOW);
        if (Objects.nonNull(isMaxWindow) && isMaxWindow) {
            initWindowsMaxState();
            return;
        }
        Double with = (Double) SystemSettingsUtil.getProperty(SystemSettingConstant.WINDOW_WITH);
        Double height = (Double) SystemSettingsUtil.getProperty(SystemSettingConstant.WINDOW_HEIGHT);
        if (with != null && height != null) {
            this.setSize(new Dimension(with.intValue(), height.intValue()));
            return;
        }
        initWindowsMaxState();
    }
    private void initWindowsMaxState() {
        Map<String, Integer> screenInfo = OSOperateUtil.getScreenInfo(this);
        Integer screenWith = screenInfo.get("width");
        Integer screenHeight = screenInfo.get("height");
        int initialWidth = (int) (screenWith * 0.8);
        int initialHeight = (int) (screenHeight * 0.8);
        this.setSize(new Dimension(initialWidth, initialHeight));
        this.setExtendedState(JFrame.MAXIMIZED_BOTH);
    }
    public void toggleDevTools() {
        isDevToolsVisible = !isDevToolsVisible;
        devToolsPanel.setVisible(isDevToolsVisible);
        if (isDevToolsVisible) {
            splitPane.setDividerLocation(0.66);
            devToolsPanel.activateDevTools();
        } else {
            splitPane.setDividerLocation(1.0);
            devToolsPanel.deactivateDevTools();
        }
        splitPane.revalidate();
        splitPane.repaint();
    }
}

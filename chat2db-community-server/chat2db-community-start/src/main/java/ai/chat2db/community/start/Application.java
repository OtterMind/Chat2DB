package ai.chat2db.community.start;

import ai.chat2db.community.updater.v2.runtime.UpdateStartupCoordinator;
import ai.chat2db.community.jcef.utils.ApplicationExitCoordinator;
import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.frame.MainJFrame;
import ai.chat2db.community.jcef.utils.CallJsFunctionUtil;
import ai.chat2db.community.jcef.utils.SingleInstanceUtil;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.sqlx.SqlxDataSourceReader;
import ai.chat2db.community.sqlx.SqlxStatusService;
import ai.chat2db.community.tools.console.ConsoleCodec;
import ai.chat2db.community.tools.console.ConsoleOutboundRegistry;
import ai.chat2db.community.tools.console.bridge.JcefServerBridgeRegistry;
import ai.chat2db.community.tools.security.AesGcmUtil;
import ai.chat2db.community.tools.sqlx.SqlxBridge;
import ai.chat2db.community.tools.sqlx.SqlxBridgeRegistry;
import ai.chat2db.community.tools.util.SystemSettingsUtil;
import ai.chat2db.community.tools.network.NetworkProxyUtil;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.util.McpRuntimeStatus;
import ai.chat2db.community.web.api.config.console.WebJcefServerBridge;
import io.micrometer.context.ContextRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Indexed;
import reactor.core.publisher.Hooks;
import reactor.util.context.ReactorContextAccessor;

import java.io.PrintStream;
import java.util.Arrays;


@SpringBootApplication
@ComponentScan(value = {"ai.chat2db.community"})
@Indexed
@EnableCaching
@EnableScheduling
@EnableAsync
@Slf4j
public class Application {

    public static void main(String[] args) {
        initializeCommunityRuntimeMode();
        if (!SingleInstanceUtil.registerDesktopInstance(args)) {
            return;
        }
        validateCommunityEncryptionKey();
        log.info("Starting Application, args: {}", Arrays.toString(args));
        log.info("Chat2DB runtime mode: {}, networkStatus: {}, basePath: {}",
                ConfigUtils.getRuntimeMode(), ConfigUtils.getNetworkStatus(), ConfigUtils.getBasePath());
        filterPrintln();
        initializeContextPropagation();
        initializeDesktopBridge();
        initializeSqlxBridge();
        NetworkProxyUtil.applySavedSettingsToJvm();
        boolean cliRuntimeMode = isCliRuntimeMode();
        UpdateStartupCoordinator.configureProduct("COMMUNITY");
        boolean updateTrial = UpdateStartupCoordinator.prepareTrialMode();
        boolean mcpEnabled = !cliRuntimeMode && !updateTrial && SystemSettingsUtil.isMcpEnabled();
        McpRuntimeStatus.initialize(mcpEnabled);
        System.setProperty("spring.ai.mcp.server.enabled", String.valueOf(mcpEnabled));
        if (cliRuntimeMode || (ConfigUtils.isDesktop() && ConfigUtils.isShowGUI() && mcpEnabled)) {
            System.setProperty("server.address", "127.0.0.1");
        }
        if (!cliRuntimeMode && ConfigUtils.isShowGUI()) {
            MainJFrame.getInstance().start(args, !updateTrial);
        }
        SpringApplication app = new SpringApplication(Application.class);
        if (!cliRuntimeMode && ConfigUtils.isDesktop() && ConfigUtils.isRelease() && !mcpEnabled) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        try {
            attachSqlxDataSourceReader(app.run(args));
            McpRuntimeStatus.markReady();
            if (!cliRuntimeMode && ConfigUtils.isDesktop() && ConfigUtils.isShowGUI()) {
                UpdateStartupCoordinator.reportReadyWhen(ApplicationExitCoordinator::isFrontendReady);
            }
        } catch (RuntimeException | Error exception) {
            UpdateStartupCoordinator.reportStartupFailure(exception);
            McpRuntimeStatus.markFailed(exception);
            throw exception;
        }
    }

    private static void initializeCommunityRuntimeMode() {
        if (System.getProperty("chat2db.runtime.mode") == null) {
            System.setProperty("chat2db.runtime.mode", "community");
        }
    }

    private static void validateCommunityEncryptionKey() {
        if (ConfigUtils.isCommunity()) {
            AesGcmUtil.configured();
        }
    }

    private static boolean isCliRuntimeMode() {
        return Boolean.parseBoolean(System.getProperty("chat2db.cli.runtime"))
                || "cli".equalsIgnoreCase(System.getProperty("chat2db.runtime.mode"));
    }

    private static void initializeContextPropagation() {
        try {
            ContextRegistry.getInstance().registerContextAccessor(new ReactorContextAccessor());
        } catch (IllegalArgumentException ignored) {
        }
        Hooks.enableAutomaticContextPropagation();
    }

    /** The settings page installs and runs the SQLX command line, which only exists on the desktop. */
    private static void initializeSqlxBridge() {
        log.info("SQLX bridge registration: chat2db.mode={}, desktop={}", System.getProperty("chat2db.mode"),
                ConfigUtils.isDesktop());
        if (!ConfigUtils.isDesktop()) {
            return;
        }
        SqlxBridgeRegistry.register(new SqlxStatusService());
    }

    /**
     * Hand the bridge the service that reads saved connections.
     * <p>
     * The startup module assembles the implementations, so the desktop shell never reaches a domain
     * module on its own. Attaching twice is harmless.
     */
    private static void attachSqlxDataSourceReader(ConfigurableApplicationContext context) {
        if (!ConfigUtils.isDesktop() || !SqlxBridgeRegistry.isRegistered()) {
            return;
        }
        IDbWorkspaceDataSourceService dataSourceService = context.getBean(IDbWorkspaceDataSourceService.class);
        SqlxBridge bridge = SqlxBridgeRegistry.getBridge();
        if (bridge instanceof SqlxStatusService service) {
            service.attach(new SqlxDataSourceReader(dataSourceService));
            log.info("SQLX bridge datasource reader attached by the startup module");
        }
    }

    private static void initializeDesktopBridge() {
        JcefServerBridgeRegistry.register(new WebJcefServerBridge());
        if (!ConfigUtils.isDesktop() || !ConfigUtils.isShowGUI()) {
            return;
        }
        ConsoleOutboundRegistry.register(message -> {
            if (JcefContext.getInstance().getBrowser_() == null) {
                return;
            }
            CallJsFunctionUtil.callHandleJavaMessage(JcefContext.getInstance().getBrowser_(), message);
        });
    }

    private static void filterPrintln() {
        if (ConfigUtils.isDesktop()) {
            if (ConfigUtils.isLocalPersistence()) {
                System.setProperty("logging.config", "classpath:logback-desktop-local.xml");
            } else {
                System.setProperty("logging.config", "classpath:logback-desktop.xml");
            }
            System.setOut(new PrintStream(System.out) {
                public void println(String x) {
                    if (org.apache.commons.lang3.StringUtils.isEmpty(x)) {
                        return;
                    }
                    if (x.startsWith(ConsoleCodec.CHAT2DB_IPC_RESPONSE)
                            && x.endsWith(ConsoleCodec.CHAT2DB_IPC_RESPONSE_END)) {
                        super.println(x);
                    }
                }
            });
        } else {
            System.setProperty("logging.config", "classpath:logback-spring.xml");
        }
    }
}

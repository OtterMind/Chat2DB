package ai.chat2db.community.web.api.config.console;

import ai.chat2db.community.domain.api.model.agent.feature.AgentWorkspaceSettings;
import ai.chat2db.community.domain.api.model.agent.skill.AiAgentSkill;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunCancelCommand;
import ai.chat2db.community.domain.api.service.agent.*;
import ai.chat2db.community.domain.api.service.ai.AiSessionFacadeService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.web.api.adapter.agent.AgentHostEnvironmentProvider;
import ai.chat2db.community.web.api.controller.*;
import ai.chat2db.community.web.api.util.ApplicationContextUtil;
import ai.chat2db.community.web.api.util.RequestMappingUtils;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.junit.jupiter.api.Assertions.*;

class DesktopAgentBridgeTest {
    private final Map<String, List<Object>> calls = new HashMap<>();
    private ApplicationContext original;
    private AnnotationConfigApplicationContext context;
    private CompletableFuture<ai.chat2db.community.domain.api.model.agent.AgentRun> runResult;
    private final WebJcefServerBridge bridge = new WebJcefServerBridge();

    @BeforeEach
    void setup() throws Exception {
        original = ApplicationContextUtil.getApplicationContext();
        reset();
        runResult = CompletableFuture.completedFuture(null);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(LocalValidatorFactoryBean.class);
        context.registerBean(ai.chat2db.community.tools.util.I18nUtils.class);
        context.registerBean("messageSource", org.springframework.context.support.StaticMessageSource.class, () -> {
            var source = new org.springframework.context.support.StaticMessageSource();
            source.setUseCodeAsDefaultMessage(true);
            return source;
        });
        IIdentityService identity = service(IIdentityService.class);
        context.registerBean(AgentController.class, () -> new AgentController(service(AgentService.class), identity,
                new AgentHostEnvironmentProvider("5.3.7-beta.3"), service(AiSessionFacadeService.class)));
        context.registerBean(AiAgentSkillController.class, () -> new AiAgentSkillController(service(IAiAgentSkillService.class)));
        context.registerBean(AgentToolGatewayController.class, () -> new AgentToolGatewayController(
                service(AgentToolAccessService.class), service(AgentApprovalService.class), service(AgentApprovalStorage.class),
                identity, service(IAiAgentQuestionService.class)));
        context.registerBean(AgentOutputController.class, () -> new AgentOutputController(service(IAiAgentOutputService.class),
                identity, List.of(service(IAgentOutputDownloadService.class))));
        context.registerBean(AgentToolSettingsController.class, () -> new AgentToolSettingsController(
                service(AgentToolAccessService.class), List.of(service(IAiAgentWorkspaceService.class))));
        context.refresh();
        new ApplicationContextUtil().setApplicationContext(context);
    }

    @AfterEach
    void cleanup() throws Exception {
        context.close();
        new ApplicationContextUtil().setApplicationContext(original);
        reset();
    }

    @Test
    void bareSkillsMappingReturnsCatalogThroughDesktopBridge() {
        assertEquals(List.of("chart"), ok("get", "/api/v3/ai/skills", null).get("data"));
    }

    @Test
    void sendsPathAndBodySeparatelyAndWaitsForAsynchronousStartAndCancel() throws Exception {
        runResult = new CompletableFuture<>();
        var future = CompletableFuture.supplyAsync(() -> ok("post", "/api/v3/ai/sessions/session-1/runs",
                "{\"modelConfigId\":\"model\",\"message\":\"hello\",\"idempotencyKey\":\"once\",\"sessionId\":\"spoof\"}"));
        Thread.sleep(100);
        assertFalse(future.isDone(), "The bridge must not serialize the unfinished future");
        runResult.complete(null);
        assertTrue((Boolean) future.get().get("success"));
        var start = (AgentRunStartCommand) calls.get("startRun").get(0);
        assertEquals("session-1", start.sessionId());
        ok("post", "/api/v3/ai/runs/run-1/cancel", "{\"sessionId\":\"session-1\"}");
        var cancel = (AgentRunCancelCommand) calls.get("cancelRun").get(0);
        assertEquals("run-1", cancel.runId());
        assertEquals("session-1", cancel.sessionId());
    }

    @Test
    void bindsEventPaginationDefaultsAndDoesNotConfuseSameTypedParameters() {
        ok("get", "/api/v3/ai/sessions/s-1/events", "{}");
        assertEquals(Arrays.asList("s-1", -1L, 0L, 200), calls.get("listEvents"));
        ok("get", "/api/v3/ai/sessions/s-2/events", "{\"limit\":15,\"afterSequence\":31}");
        assertEquals(Arrays.asList("s-2", -1L, 31L, 15), calls.get("listEvents"));
        assertEquals(false, request("post", "/api/v3/ai/sessions/s-2/events", "{}").get("success"));
    }

    @Test
    void validatesBodiesBeforeCallingServicesAndPropagatesAsyncFailures() {
        assertEquals(false, request("post", "/api/v3/ai/sessions/s/runs", "{\"message\":\"hello\"}").get("success"));
        assertFalse(calls.containsKey("startRun"));
        runResult = CompletableFuture.failedFuture(new BusinessException("common.permissionDenied"));
        var response = request("post", "/api/v3/ai/sessions/s/runs",
                "{\"modelConfigId\":\"model\",\"message\":\"hello\",\"idempotencyKey\":\"once\"}");
        assertEquals(false, response.get("success"));
        assertEquals("common.permissionDenied", response.get("errorCode"));
    }

    @Test
    void supportsApprovalsQuestionsAndHistoryRoutes() {
        ok("get", "/api/v3/ai/sessions/s/approvals", "{}");
        ok("post", "/api/v3/ai/sessions/s/approvals", "{\"approvalId\":\"a\",\"decision\":\"DENY\"}");
        assertEquals(Arrays.asList("s", "a", -1L, ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecision.DENY),
                calls.get("decide"));
        ok("get", "/api/v3/ai/sessions/s/questions", "{}");
        ok("post", "/api/v3/ai/sessions/s/questions/answer", "{\"questionId\":\"q\",\"text\":\"answer\"}");
        assertEquals("s", calls.get("answer").get(0));
        ok("post", "/api/v3/ai/sessions/s/rename", "{\"title\":\"Renamed\"}");
        assertEquals(Arrays.asList("s", -1L, "Renamed"), calls.get("renameSession"));
        ok("post", "/api/v3/ai/sessions/s/delete", "{}");
        assertEquals(Arrays.asList("s", -1L), calls.get("deleteSession"));
    }

    @Test
    void bindsTwoPathVariablesAndOptionalOutputSearchParameters() {
        ok("get", "/api/v3/ai/sessions/s/outputs/f/read", "{\"limit\":20}");
        assertEquals(Arrays.asList("s", -1L, "f", null, null, 20), calls.get("read"));
        ok("get", "/api/v3/ai/sessions/s/outputs/f/search", "{\"pattern\":\"error\"}");
        assertEquals(Arrays.asList("s", -1L, "f", "error", true, false, null, null), calls.get("search"));
        ok("post", "/api/v3/ai/sessions/s/outputs/f/download-path", null);
        assertEquals(Arrays.asList("s", -1L, "f"), calls.get("save"));
    }

    @Test
    void supportsToolsAndNativeDirectorySelectionWithDesktopContext() {
        ok("get", "/api/v3/ai/features/tools", null);
        ok("get", "/api/v3/ai/features/tools/settings", null);
        ok("post", "/api/v3/ai/features/tools/settings", "{\"workingDirectory\":\"/tmp/agent-test\"}");
        assertEquals(List.of("/tmp/agent-test"), calls.get("update"));
        ok("post", "/api/v3/ai/features/tools/select-directory", null);
        assertFalse(DesktopBridgeRequestContext.isActive());
        assertThrows(SecurityException.class, () -> context.getBean(AgentToolSettingsController.class).selectDirectory());
    }

    private Map<String, Object> ok(String method, String url, String body) {
        var response = request(method, url, body);
        assertEquals(true, response.get("success"), () -> url + ": " + response);
        return response;
    }

    private Map<String, Object> request(String method, String url, String body) {
        ConsoleMessage message = new ConsoleMessage();
        message.setUuid(UUID.randomUUID().toString());
        message.setActionType("execute"); message.setMethod(method); message.setRequestUrl(url); message.setMessage(body);
        return bridge.doController(message).getMessage();
    }

    private <T> T service(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            calls.put(method.getName(), args == null ? List.of() : Arrays.asList(args));
            return switch (method.getName()) {
                case "currentUserId" -> -1L;
                case "prepare" -> List.of(new AiAgentSkill("chart", "/tmp/chart/SKILL.md", "digest"));
                case "startRun", "cancelRun" -> runResult;
                case "get", "update" -> new AgentWorkspaceSettings("/tmp/agent-test");
                case "listEvents", "pending", "list", "listTools" -> List.of();
                default -> null;
            };
        }));
    }

    private void reset() throws Exception {
        var initialized = RequestMappingUtils.class.getDeclaredField("initialized");
        initialized.setAccessible(true); initialized.setBoolean(null, false);
        var mappings = RequestMappingUtils.class.getDeclaredField("requestMappingInfoMap");
        mappings.setAccessible(true); mappings.set(null, Map.of());
    }
}

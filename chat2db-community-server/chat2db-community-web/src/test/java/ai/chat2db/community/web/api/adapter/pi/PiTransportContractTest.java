package ai.chat2db.community.web.api.adapter.pi;

import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.tools.wrapper.result.*;
import ai.chat2db.community.web.api.config.console.WebJcefServerBridge;
import ai.chat2db.community.web.api.controller.*;
import ai.chat2db.community.web.api.util.ApplicationContextUtil;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.mockito.stubbing.Answer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class PiTransportContractTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final WebJcefServerBridge desktop = new WebJcefServerBridge();
    private AnnotationConfigApplicationContext context;
    private ApplicationContext previous;
    private PiOperationRegistry registry;
    private MockMvc http;
    private AgentController sessions;

    @BeforeEach
    void setup() {
        previous = ApplicationContextUtil.getApplicationContext();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(LocalValidatorFactoryBean.class);
        context.registerBean("messageSource", StaticMessageSource.class, () -> {
            var messages = new StaticMessageSource();
            messages.setUseCodeAsDefaultMessage(true);
            messages.addMessage("common.permissionDenied", Locale.CHINA, "权限不足");
            messages.addMessage("common.permissionDenied", Locale.US, "Permission denied");
            return messages;
        });
        context.registerBean(I18nUtils.class);
        context.refresh();
        new ApplicationContextUtil().setApplicationContext(context);
        sessions = controller(AgentController.class);
        registry = new PiOperationRegistry(json, context.getBean(LocalValidatorFactoryBean.class), sessions,
                controller(AiAgentSkillController.class), controller(AgentFeatureController.class),
                controller(AgentToolSettingsController.class), controller(AgentToolGatewayController.class),
                controller(AgentOutputController.class), controller(AiChatController.class));
        PiController entry = new PiController(registry);
        context.getBeanFactory().registerSingleton("piController", entry);
        http = MockMvcBuilders.standaloneSetup(entry).build();
    }

    @AfterEach
    void close() {
        LocaleContextHolder.resetLocaleContext();
        context.close();
        new ApplicationContextUtil().setApplicationContext(previous);
    }

    @Test
    void everyClientOperationUsesTheSameBindingAndResponseOnBothTransports() throws Exception {
        Map<String, String> payloads = payloads();
        assertEquals(registry.operationNames(), payloads.keySet());
        var source = Files.readString(Path.of("../../chat2db-community-client/src/service/pi/contract.ts"));
        var matcher = Pattern.compile("(?m)^  '([^']+)': Operation<").matcher(source);
        Set<String> clientOperations = new HashSet<>();
        while (matcher.find()) clientOperations.add(matcher.group(1));
        assertEquals(registry.operationNames(), clientOperations, "Both client and server contracts must cover every operation");
        Set<String> nativeOnly = Set.of("workspace.selectDirectory", "outputs.save", "attachments.parseLocal");
        for (var operation : payloads.entrySet()) {
            ObjectNode request = request(operation.getKey(), operation.getValue());
            JsonNode web = http(request), jcef = desktop(request);
            assertTrue(jcef.path("success").asBoolean(), operation.getKey() + ": " + jcef);
            if (nativeOnly.contains(operation.getKey())) {
                assertFalse(web.path("success").asBoolean());
                assertEquals("common.permissionDenied", web.path("errorCode").asText());
            } else {
                assertEquals(web, jcef, operation.getKey());
                assertTrue(web.path("success").asBoolean());
            }
            assertEquals(request.path("requestId"), jcef.path("requestId"));
        }
    }

    @Test
    void invalidAndUnknownRequestsFailIdenticallyWithoutCallingBusinessCode() throws Exception {
        for (ObjectNode request : List.of(request("does.not.exist", "{}"), request("runs.start", "{}"),
                request("runtime.enable", "{}"), request("runtime.enable", "{\"confirmed\":false}"),
                request("events.list", "{\"sessionId\":\"s\",\"limit\":-1}"),
                request("skills.list", "[]"), request("skills.list", "{}").put("protocolVersion", 99))) {
            assertEquals(http(request), desktop(request));
            assertFalse(desktop(request).path("success").asBoolean());
        }
        verifyNoInteractions(sessions);
    }

    @Test
    void asynchronousFailuresUseTheSameErrorContract() throws Exception {
        when(sessions.startRun(anyString(), any())).thenReturn(
                CompletableFuture.failedFuture(new BusinessException("common.permissionDenied")));
        ObjectNode request = request("runs.start", payloads().get("runs.start"));
        JsonNode web = http(request), jcef = desktop(request);
        assertEquals(web, jcef);
        assertEquals("common.permissionDenied", web.path("errorCode").asText());
    }

    @Test
    void asynchronousErrorsKeepTheCallersLocaleAndRestoreTheCompletionThread() throws Exception {
        var completion = new CompletableFuture<DataResult<ai.chat2db.community.domain.api.model.agent.AgentRun>>();
        when(sessions.startRun(anyString(), any())).thenReturn(completion);
        LocaleContextHolder.setLocale(Locale.CHINA);
        var response = registry.invoke(request("runs.start", payloads().get("runs.start")));
        LocaleContextHolder.setLocale(Locale.US);
        completion.completeExceptionally(new BusinessException("common.permissionDenied"));
        assertEquals("权限不足", response.toCompletableFuture().get().path("errorMessage").asText());
        assertEquals(Locale.US, LocaleContextHolder.getLocale());
    }

    @Test
    void jsonDatesAndFalseValuesStayStableAcrossBothAdapters() throws Exception {
        JsonNode response = desktop(request("tools.setEnabled", payloads().get("tools.setEnabled")));
        assertEquals("2026-01-02T03:04:05", response.path("data").path("time").asText());
        assertFalse(response.path("data").path("arguments").get(1).path("enabled").asBoolean());
        assertEquals(response, http(request("tools.setEnabled", payloads().get("tools.setEnabled"))));
    }

    private <T> T controller(Class<T> type) {
        Answer<Object> reply = call -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("method", call.getMethod().getName()); data.put("arguments", Arrays.asList(call.getArguments()));
            data.put("time", LocalDateTime.of(2026, 1, 2, 3, 4, 5));
            Class<?> result = call.getMethod().getReturnType();
            if (CompletionStage.class.isAssignableFrom(result)) return CompletableFuture.completedFuture(DataResult.of(data));
            if (ListResult.class.isAssignableFrom(result)) return ListResult.of(List.of(data));
            if (ActionResult.class.isAssignableFrom(result)) return ActionResult.isSuccess();
            return DataResult.of(data);
        };
        return mock(type, reply);
    }

    private ObjectNode request(String operation, String payload) throws Exception {
        ObjectNode request = json.createObjectNode();
        request.put("protocolVersion", 1).put("requestId", "parity-check").put("operation", operation);
        request.set("payload", json.readTree(payload)); return request;
    }

    private JsonNode http(JsonNode request) throws Exception {
        var pending = http.perform(post(PiOperationRegistry.ENDPOINT).contentType("application/json").header("Accept-Language", "zh-CN")
                .content(json.writeValueAsBytes(request))).andReturn();
        var result = http.perform(asyncDispatch(pending)).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode desktop(JsonNode request) throws Exception {
        ConsoleMessage message = new ConsoleMessage();
        message.setUuid("desktop-uuid"); message.setActionType("execute"); message.setMethod("post");
        message.setRequestUrl(PiOperationRegistry.ENDPOINT); message.setMessage(json.writeValueAsString(request));
        message.setHeaders(Map.of("Accept-Language", "zh-CN"));
        return json.readTree(json.writeValueAsBytes(desktop.doController(message).getMessage()));
    }

    private Map<String, String> payloads() {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (String operation : List.of("skills.list", "runtime.list", "runtime.check", "runtime.disable",
                "bash.check", "bash.disable", "tools.list", "workspace.get", "workspace.selectDirectory", "sessions.list")) {
            inputs.put(operation, "{}");
        }
        for (String operation : List.of("runtime.enable", "bash.enable")) inputs.put(operation, "{\"confirmed\":true}");
        inputs.put("tools.setEnabled", "{\"toolName\":\"read\",\"enabled\":false}");
        inputs.put("workspace.set", "{\"workingDirectory\":\"/fixture\"}");
        inputs.put("sessions.create", "{\"message\":\"hello\",\"runtimeType\":\"PI\",\"modelConfigId\":\"model\"}");
        inputs.put("sessions.get", "{\"sessionId\":\"session\",\"sessionVersion\":2}");
        inputs.put("sessions.rename", "{\"sessionId\":\"session\",\"title\":\"renamed\"}");
        for (String operation : List.of("sessions.delete", "events.list", "approvals.list", "questions.list")) {
            inputs.put(operation, "{\"sessionId\":\"session\"}");
        }
        inputs.put("runs.start", "{\"sessionId\":\"session\",\"message\":\"hello\",\"modelConfigId\":\"model\",\"idempotencyKey\":\"once\"}");
        inputs.put("runs.cancel", "{\"sessionId\":\"session\",\"runId\":\"run\"}");
        inputs.put("approvals.decide", "{\"sessionId\":\"session\",\"approvalId\":\"approval\",\"approved\":false}");
        inputs.put("questions.answer", "{\"sessionId\":\"session\",\"questionId\":\"question\",\"optionId\":\"all\"}");
        for (String operation : List.of("outputs.read", "outputs.save")) inputs.put(operation, "{\"sessionId\":\"session\",\"artifactId\":\"output\"}");
        inputs.put("outputs.search", "{\"sessionId\":\"session\",\"artifactId\":\"output\",\"pattern\":\"error\",\"literal\":false}");
        inputs.put("models.prepare", "{\"name\":\"test\",\"provider\":\"OPENAI\",\"model\":\"local-model\"}");
        inputs.put("attachments.parseLocal", "{\"filePath\":\"/fixture.txt\"}");
        return inputs;
    }
}

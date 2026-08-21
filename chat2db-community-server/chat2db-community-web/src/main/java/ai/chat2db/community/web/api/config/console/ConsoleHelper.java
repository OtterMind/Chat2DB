package ai.chat2db.community.web.api.config.console;

import ai.chat2db.community.domain.api.model.workspace.Node;
import ai.chat2db.community.domain.api.model.operation.Operation;
import ai.chat2db.community.tools.console.ConsoleCodec;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleObjectConverter;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.http.LocalCookie;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.SystemException;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.tools.exception.NeedLoggedInBusinessException;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.tools.util.LogUtils;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceQueryRequest;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceNamespaceResponse;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceResponse;
import ai.chat2db.community.web.api.model.request.operation.saved.OperationQueryRequest;
import ai.chat2db.community.web.api.model.http.CookieUtil;
import ai.chat2db.community.web.api.storage.WorkspaceStorageWebFacade;
import ai.chat2db.community.web.api.util.ApplicationContextUtil;
import ai.chat2db.community.web.api.util.RequestMappingUtils;
import ai.chat2db.community.tools.util.ExceptionUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dtflys.forest.exceptions.ForestNetworkException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.beanutils.ConvertUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConsoleHelper {

    public static final String CHAT2DB_IPC_RESPONSE = ConsoleCodec.CHAT2DB_IPC_RESPONSE;

    public static final String CHAT2DB_IPC_RESPONSE_END = ConsoleCodec.CHAT2DB_IPC_RESPONSE_END;

    public static final String CHAT2DB_IPC_RESPONSE_SERVICE_STATUS_SUCCESS =
            ConsoleCodec.CHAT2DB_IPC_RESPONSE_SERVICE_STATUS_SUCCESS;

    private static ThreadPoolExecutor executor = new ThreadPoolExecutor(100,
            200,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>()
    );
    public static boolean init = false;

    public static synchronized void init() {
        init = true;
    }

    private static final Logger log = org.slf4j.LoggerFactory.getLogger("CHANNEL_LOGGER");

    public static void run(String... args) {
        new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            sendMessage(CHAT2DB_IPC_RESPONSE_SERVICE_STATUS_SUCCESS);
            while (true) {
                onMessage(scanner.nextLine());
            }
        }).start();

        new Thread(() -> {
            long i = 0;
            while (true) {
                try {
                    i++;
                    Thread.sleep(1000 * 60);
                    System.gc();
                } catch (Exception e) {
                    log.error("error", e);
                }
            }
        }).start();
    }

    public static void onMessage(String message) {
        try {
            if (StringUtils.isBlank(message)) {
                return;
            }
            List<String> dataList = getValidInput(message);
            if (CollectionUtils.isEmpty(dataList)) {
                return;
            }
            for (String data : dataList) {
                executor.submit(() -> {
                    ConsoleMessage wsMessage = null;
                    ConsoleResult result = null;
                    try {
                        wsMessage = JSONObject.parseObject(decompress(data), ConsoleMessage.class);
                        if (wsMessage == null) {
                            return;
                        }
                        log.info("request url:{},uuid:{}", wsMessage.getRequestUrl(), wsMessage.getUuid());
                        result = ConsoleResult.builder()
                                .uuid(wsMessage.getUuid())
                                .actionType(wsMessage.getActionType())
                                .requestUrl(wsMessage.getRequestUrl())
                                .method(wsMessage.getMethod())
                                .build();
                        setHeaders(wsMessage);

                        if ("/api/system".equals(wsMessage.getRequestUrl())) {
                            result.setMessage(Map.of("success", true));
                            sendMessage(result);
                            return;
                        }

                        if ("/api/namespaces/tree_list".equals(wsMessage.getRequestUrl())) {
                            ListResult<Node> r = WorkspaceStorageWebFacade.getTree();
                            r.setSuccess(true);
                            result.setMessage(ConsoleObjectConverter.object2map(r));
                            sendMessage(result);
                            return;
                        }
                        if ("/api/namespaces/data_source_list".equals(wsMessage.getRequestUrl())) {
                            DataResult<DataSourceNamespaceResponse> r = WorkspaceStorageWebFacade.getNamespaceDatasource();
                            r.setSuccess(true);
                            result.setMessage(ConsoleObjectConverter.object2map(r));
                            sendMessage(result);
                            return;
                        }
                        if ("/api/connection/datasource/list".equals(wsMessage.getRequestUrl())) {
                            DataSourceQueryRequest request = new DataSourceQueryRequest();
                            request.setPageNo(1);
                            request.setPageSize(2000);
                            WebPageResult<DataSourceResponse> r = WorkspaceStorageWebFacade.getDataSourceList(request);
                            r.setSuccess(true);
                            result.setMessage(ConsoleObjectConverter.object2map(r));
                            sendMessage(result);
                            return;
                        }

                        if ("/api/operation/saved/list".equals(wsMessage.getRequestUrl())) {
                            OperationQueryRequest request = JSON.parseObject(wsMessage.getMessage(), OperationQueryRequest.class);
                            WebPageResult<Operation> r = WorkspaceStorageWebFacade.consoleList(request);
                            r.setSuccess(true);
                            result.setMessage(ConsoleObjectConverter.object2map(r));
                            sendMessage(result);
                            return;
                        }

                        if ("/api/operation/saved".equals(wsMessage.getRequestUrl()) && "GET".equalsIgnoreCase(wsMessage.getMethod())) {
                            Map<String, Object> formParams = JSON.parseObject(wsMessage.getMessage(), Map.class);
                            DataResult<Operation> r = WorkspaceStorageWebFacade.getConsole((Long) formParams.get("id"));
                            r.setSuccess(true);
                            result.setMessage(ConsoleObjectConverter.object2map(r));
                            sendMessage(result);
                            return;
                        }
                        while (!init) {
                            Thread.sleep(20);
                        }
                        result = doController(wsMessage);
                        sendMessage(result);
                    } catch (Exception e) {
                        log.error("ConsoleInputListener2 onMessage error", e);
                        sendMessage(error(e, wsMessage));
                    }
                });
            }
        } catch (Exception e) {
            log.error("onMessage error", e);
            return;
        }
    }

    public static final String regex = ConsoleCodec.REQUEST_REGEX;

    private static StringBuilder sb = new StringBuilder();

    public static List<String> getValidInput(String message) {
        message = sb.append(message).toString();
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(message);
        List<String> result = new ArrayList<>();
        int lastMatchEnd = 0;
        while (matcher.find()) {
            lastMatchEnd = matcher.end();
            String value = matcher.group(1);
            if (StringUtils.isNotBlank(value)) {
                result.add(value);
            }
        }
        if (message.length() == lastMatchEnd) {
            sb = new StringBuilder();
        } else if (message.length() > lastMatchEnd) {
            sb = new StringBuilder(message.substring(lastMatchEnd));
        }

        return result;
    }


    public static ConsoleResult error(Exception e, ConsoleMessage wsMessage) {
        ConsoleResult wsResult = new ConsoleResult();
        ActionResult actionResult = new ActionResult();
        actionResult.setSuccess(false);
        actionResult.setErrorCode(e.getMessage());
        actionResult.setErrorMessage(e.getMessage());
        wsResult.setActionType(ConsoleMessage.ActionType.ERROR);
        if (wsMessage != null) {
            wsResult.setUuid(wsMessage.getUuid());
            wsResult.setRequestUrl(wsMessage.getRequestUrl());
            wsResult.setMethod(wsMessage.getMethod());
        }
        try {
            wsResult.setMessage(ConsoleObjectConverter.object2map(actionResult));
        } catch (Exception ee) {
            log.error("error", ee);
        }
        return wsResult;
    }

    public static void sendMessage(ConsoleResult wsResult) {
        if(wsResult == null){
            return;
        }
        String result = CHAT2DB_IPC_RESPONSE + compress(JSON.toJSONString(wsResult)) + CHAT2DB_IPC_RESPONSE_END;
        log.info("returnMessage:{}", result);
        System.out.println(new String(result.getBytes(), StandardCharsets.UTF_8));
    }

    public static void sendMessage(String wsResult) {
        log.info("returnMessage:{}", wsResult);
        String result = CHAT2DB_IPC_RESPONSE + compress(wsResult) + CHAT2DB_IPC_RESPONSE_END;
        System.out.println(new String(result.getBytes(), StandardCharsets.UTF_8));
    }


    public static ConsoleResult doController(ConsoleMessage message) {
        ConsoleResult result = new ConsoleResult();
        result.setUuid(message.getUuid());
        result.setActionType(message.getActionType());
        result.setRequestUrl(message.getRequestUrl());
        result.setMethod(message.getMethod());
        setHeaders(message);
        try {
            RequestMappingInfo iRequestMappingInfo = RequestMappingUtils.getRequestMappingInfo(message.getRequestUrl(), message.getMethod());
            if (iRequestMappingInfo == null) {
                log.error("No request mapping found for url: {}, method: {}", message.getRequestUrl(), message.getMethod());
                ActionResult result1 = ActionResult.fail("common.notFound", "No handler found for " + message.getRequestUrl(), null);
                result.setMessage(ConsoleObjectConverter.object2map(result1));
                return result;
            }
            Object c = ApplicationContextUtil.getBeanOfType(iRequestMappingInfo.getController());
            Object[] o = getValues(message.getMessage(), iRequestMappingInfo.getParams(),result);
            Class controllerClass = iRequestMappingInfo.getController();
            String method = iRequestMappingInfo.getMethod();
            Class[] params = iRequestMappingInfo.getParams();
            Object object = controllerClass.getMethod(method, params).invoke(c, o);
            result.setMessage(ConsoleObjectConverter.object2map(object));
            if("/api/v3/ai/chat/stream".equals(message.getRequestUrl())){
                return null;
            }
            return result;
        } catch (NeedLoggedInBusinessException e) {
            log.error("NeedLoggedInBusinessException error, param {}", JSON.toJSONString(message), e);
            ActionResult result1 = ActionResult.fail(e.getCode(), e.getMessage(), null);
            result.setMessage(ConsoleObjectConverter.object2map(result1));
            return result;
        } catch (BusinessException e) {
            log.error("BusinessException error, param {}", JSON.toJSONString(message), e);
            ActionResult result1 = ActionResult.fail(e.getCode(), e.getMessage(), null);
            result.setMessage(ConsoleObjectConverter.object2map(result1));
            return result;
        } catch (ForestNetworkException ex) {
            log.error("ForestNetworkException onMessage error", ex);
            String errorCode = "api.networkError";
            ActionResult result1 = ActionResult.fail(errorCode, errorCode, null);
            result.setMessage(ConsoleObjectConverter.object2map(result1));
            return result;
        } catch (SystemException e) {
            log.error("SystemException error, param {}", JSON.toJSONString(message), e);
            ActionResult result1 = ActionResult.fail(e.getCode(), e.getMessage(), null);
            result.setMessage(ConsoleObjectConverter.object2map(result1));
            return result;
        } catch (InvocationTargetException e) {
            Throwable throwable = e.getTargetException();
            if (throwable != null) {
                if (throwable instanceof NeedLoggedInBusinessException) {
                    log.error("NeedLoggedInBusinessException error, param {}", JSON.toJSONString(message), e);
                    ActionResult result1 = ActionResult.fail(((NeedLoggedInBusinessException) throwable).getCode(), e.getMessage(), null);
                    result.setMessage(ConsoleObjectConverter.object2map(result1));
                    return result;
                } else if (throwable instanceof ForestNetworkException) {
                    log.error("ForestNetworkException onMessage error", e);
                    String errorCode = "api.networkError";
                    ActionResult result1 = ActionResult.fail(errorCode, errorCode, null);
                    result.setMessage(ConsoleObjectConverter.object2map(result1));
                    return result;
                } else if (throwable instanceof BusinessException) {
                    log.error("BusinessException error, param {}", JSON.toJSONString(message), e);
                    ActionResult result1 = ActionResult.fail(((BusinessException) throwable).getCode()
                            , I18nUtils.getMessage(((BusinessException) throwable).getCode()), null);
                    result.setMessage(ConsoleObjectConverter.object2map(result1));
                    return result;
                } else if (throwable instanceof SystemException) {
                    log.error("SystemException error, param {}", JSON.toJSONString(message), e);
                    ActionResult result1 = ActionResult.fail(((SystemException) throwable).getCode(), throwable.getMessage(), null);
                    result.setMessage(ConsoleObjectConverter.object2map(result1));
                    return result;
                }
            }
            log.error("invoke error, param {}", JSON.toJSONString(message), e);
            Throwable rootCause = ExceptionUtils.getRootCause(e);
            if (rootCause == null) {
                rootCause = e;
            }
            String msg = rootCause.getMessage() == null ? "" : rootCause.getMessage();
            String errorCode = msg + " " + rootCause.getClass().getSimpleName();
            ActionResult result1 = ActionResult.fail(errorCode, rootCause.getClass().getName(), ExceptionUtils.getErrorInfoFromException(e));
            result.setMessage(ConsoleObjectConverter.object2map(result1));
            return result;
        } catch (Exception e) {
            log.error("invoke error, param {}", JSON.toJSONString(message), e);
            ActionResult result1 = ActionResult.fail("common.systemError", I18nUtils.getMessage("common.systemError"), ExceptionUtils.getErrorInfoFromException(e));
            result.setMessage(ConsoleObjectConverter.object2map(result1));
            return result;
        }


    }


    public static void setHeaders(ConsoleMessage message) {
        if (message.getHeaders() != null) {
            String language = (String) message.getHeaders().get(CookieUtil.ACCEPT_LANGUAGE);
            if (com.dtflys.forest.utils.StringUtils.isNotEmpty(language)) {
                LocaleContextHolder.setLocale(resolveLocale(language));
            }
            for (Map.Entry<String, Object> entry : message.getHeaders().entrySet()) {
                LocalCookie.setHeader(entry.getKey(), (String) entry.getValue());
            }
        }
        if (StringUtils.isNotBlank(message.getUuid())) {
            LogUtils.setTraceId(message.getUuid().replaceAll("-", ""));
            MDC.put(LogUtils.TRACE_ID, message.getUuid().replaceAll("-", ""));
        }
    }

    static Locale resolveLocale(String language) {
        if (language.startsWith("zh")) {
            return Locale.CHINA;
        }
        if (language.startsWith("ja")) {
            return Locale.JAPAN;
        }
        return Locale.US;
    }


    public static Object[] getValues(String message, Class[] clazzArray,ConsoleResult result) {
        Object o = null;
        if (clazzArray == null || clazzArray.length == 0) {
            return null;
        }
        Object[] values = new Object[clazzArray.length];
        if (clazzArray.length == 1) {
            Class clazz = clazzArray[0];
            if (ClassUtils.isPrimitiveOrWrapper(clazz) || String.class.equals(clazz)) {
                Map<String, Object> value = JSON.parseObject(message, Map.class);
                if (value != null && !value.isEmpty()) {
                    values[0] = ConvertUtils.convert(value.values().iterator().next(), clazz);
                } else {
                    values[0] = null;
                }
            } else if (MultipartFile.class.equals(clazz)) {
                values[0] = getFile(message);
            } else if (MultipartFile[].class.equals(clazz)) {
                values[0] = getFiles(message);
            }else if(ChatRequest.class.equals(clazz)){
                ChatRequest param = (ChatRequest)getObject(message, ChatRequest.class);
                param.setConsoleResult(result);
                values[0] = param;
            } else {
                values[0] = getObject(message, clazz);
            }
        } else {
            throw new RuntimeException("not support");
        }
        return values;
    }


    private static Object getFile(String message) {
        Map<String, List<String>> multipartFiles = JSON.parseObject(message, Map.class);
        List<String> fileNames = multipartFiles.get("file");
        if (CollectionUtils.isNotEmpty(fileNames)) {
            for (String fileName : fileNames) {
                return getMyMultipartFile(fileName);
            }
        }
        return null;
    }

    private static Object getFiles(String message) {
        MyMultipartFile[] mockMultipartFiles = null;
        Map<String, List<String>> multipartFiles = JSON.parseObject(message, Map.class);
        List<String> fileNames = multipartFiles.get("file");
        if (CollectionUtils.isNotEmpty(fileNames)) {
            mockMultipartFiles = new MyMultipartFile[fileNames.size()];
            int i = 0;
            for (String fileName : fileNames) {
                mockMultipartFiles[i] = getMyMultipartFile(fileName);
                i++;
            }
        }
        return mockMultipartFiles;
    }

    private static MyMultipartFile getMyMultipartFile(String fileName) {
        Path path = Path.of(fileName);
        byte[] content = new byte[0];
        try {
            content = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new MyMultipartFile(
                "file",
                fileName,
                "text/plain",
                content
        );
    }

    private static Object getObject(String message, Class clazz) {
        if (StringUtils.isBlank(message)) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return mapper.readValue(message, clazz);
        } catch (Exception e) {
            return JSON.parseObject(message, clazz);
        }
    }


    public static String compress(String data) {
        return ConsoleCodec.compress(data);
    }

    public static String decompress(String data) {
        return ConsoleCodec.decompress(data);
    }
}

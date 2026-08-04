package ai.chat2db.community.web.api.util;

import ai.chat2db.community.web.api.config.console.RequestMappingInfo;

import jakarta.servlet.ServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class RequestMappingUtils {

    private static volatile Map<String, List<RequestMappingInfo>> requestMappingInfoMap = Collections.emptyMap();
    private static volatile boolean initialized = false;

    private static synchronized void init() {
        if (initialized) {
            return;
        }
        ApplicationContext context = ApplicationContextUtil.getApplicationContext();
        if (context == null) {
            throw new IllegalStateException("Spring application context is not initialized");
        }

        Map<String, List<RequestMappingInfo>> mappings = new HashMap<>();
        Map<String, Object> beansWithAnnotation = context.getBeansWithAnnotation(RestController.class);
        for (Object bean : beansWithAnnotation.values()) {
            Class<?> beanClass = AopProxyUtils.ultimateTargetClass(bean);
            RequestMapping restController = beanClass.getAnnotation(RequestMapping.class);
            String prefixUrl = "";
            if (restController != null && restController.value().length > 0) {
                prefixUrl = restController.value()[0];
            }
            Method[] methods = beanClass.getDeclaredMethods();
            for (Method method : methods) {
                if (method.isAnnotationPresent(RequestMapping.class)) {
                    RequestMapping annotation = method.getAnnotation(RequestMapping.class);
                    addRequestMappingInfoMap(mappings, prefixUrl, beanClass, method, annotation.value(), annotation.method());
                }
                if (method.isAnnotationPresent(PostMapping.class)) {
                    PostMapping annotation = method.getAnnotation(PostMapping.class);
                    addRequestMappingInfoMap(mappings, prefixUrl, beanClass, method, annotation.value(), RequestMethod.POST);
                }
                if (method.isAnnotationPresent(GetMapping.class)) {
                    GetMapping annotation = method.getAnnotation(GetMapping.class);
                    addRequestMappingInfoMap(mappings, prefixUrl, beanClass, method, annotation.value(), RequestMethod.GET);
                }
                if (method.isAnnotationPresent(DeleteMapping.class)) {
                    DeleteMapping annotation = method.getAnnotation(DeleteMapping.class);
                    addRequestMappingInfoMap(mappings, prefixUrl, beanClass, method, annotation.value(), RequestMethod.DELETE);
                }
                if (method.isAnnotationPresent(PutMapping.class)) {
                    PutMapping annotation = method.getAnnotation(PutMapping.class);
                    addRequestMappingInfoMap(mappings, prefixUrl, beanClass, method, annotation.value(), RequestMethod.PUT);
                }
            }
        }
        requestMappingInfoMap = mappings;
        initialized = true;
        log.info("Initialized {} desktop request mappings", mappings.size());
    }

    private static void addRequestMappingInfoMap(Map<String, List<RequestMappingInfo>> mappings, String prefixUrl,
            Class<?> beanClass, Method method, String[] values, RequestMethod... requestMethods) {
        if (values != null && values.length > 0) {
            for (String value : values) {
                RequestMappingInfo requestMappingInfo = new RequestMappingInfo();
                if (requestMethods != null) {
                    List<String> names = Arrays.stream(requestMethods).map(RequestMethod::name).collect(Collectors.toList());
                    requestMappingInfo.setRequestMethods(names);
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (!StringUtils.isEmpty(value) && !value.startsWith("/") || countRequestParameters(parameterTypes) > 1) {
                    log.error("-----RequestMappingUtils addRequestMappingInfoMap error, beanClass:" + beanClass);
                }
                String url = prefixUrl + value;
                requestMappingInfo.setUrl(url);
                requestMappingInfo.setController(beanClass);
                requestMappingInfo.setMethod(method.getName());
                requestMappingInfo.setParams(parameterTypes);
                List<RequestMappingInfo> requestMappingInfos = mappings.get(url);
                if (CollectionUtils.isEmpty(requestMappingInfos)) {
                    requestMappingInfos = new ArrayList<>();
                }
                requestMappingInfos.add(requestMappingInfo);
                mappings.put(url, requestMappingInfos);
            }
        }
    }

    private static long countRequestParameters(Class<?>[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return 0L;
        }
        return Arrays.stream(parameterTypes)
                .filter(parameterType -> !ServletResponse.class.isAssignableFrom(parameterType))
                .count();
    }

    public static RequestMappingInfo getRequestMappingInfo(String url, String requestMethod) {
        if (!initialized) {
            init();
        }
        List<RequestMappingInfo> requestMappingInfos = requestMappingInfoMap.get(url);
        if (CollectionUtils.isEmpty(requestMappingInfos)) {
            return null;
        }
        if (requestMethod != null) {
            requestMethod = requestMethod.toUpperCase();
        }
        for (RequestMappingInfo requestMappingInfo : requestMappingInfos) {
            if (CollectionUtils.isEmpty(requestMappingInfo.getRequestMethods()) || requestMappingInfo.getRequestMethods().contains(requestMethod)) {
                return requestMappingInfo;
            }
        }
        return null;
    }
}

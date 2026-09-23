package ai.chat2db.community.web.api.util;

import ai.chat2db.community.web.api.config.console.RequestMappingInfo;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;
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
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

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
            RequestMapping controllerMapping = AnnotatedElementUtils.findMergedAnnotation(beanClass, RequestMapping.class);
            String[] prefixes = controllerMapping == null || controllerMapping.value().length == 0
                    ? new String[]{""} : controllerMapping.value();
            for (Method method : beanClass.getMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                for (String prefix : prefixes) {
                    addRequestMappingInfoMap(mappings, prefix, beanClass, method,
                            mapping.value().length == 0 ? new String[]{""} : mapping.value(), mapping.method());
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

    public static Map<String, String> pathVariables(RequestMappingInfo mapping, String url) {
        Map<String, String> variables = new HashMap<>(PATH_MATCHER.extractUriTemplateVariables(mapping.getUrl(), url));
        variables.replaceAll((name, value) -> UriUtils.decode(value, StandardCharsets.UTF_8));
        return variables;
    }

    public static RequestMappingInfo getRequestMappingInfo(String url, String requestMethod) {
        if (!initialized) {
            init();
        }
        // Literal routes take precedence; select the most specific matching template for this verb.
        return requestMappingInfoMap.keySet().stream()
                .filter(pattern -> PATH_MATCHER.match(pattern, url))
                .sorted(PATH_MATCHER.getPatternComparator(url))
                .flatMap(pattern -> requestMappingInfoMap.get(pattern).stream())
                .filter(mapping -> CollectionUtils.isEmpty(mapping.getRequestMethods())
                        || mapping.getRequestMethods().stream().anyMatch(verb -> verb.equalsIgnoreCase(requestMethod)))
                .findFirst().orElse(null);
    }
}

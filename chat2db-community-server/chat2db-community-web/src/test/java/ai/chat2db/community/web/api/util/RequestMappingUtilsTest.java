package ai.chat2db.community.web.api.util;

import ai.chat2db.community.web.api.config.console.RequestMappingInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestMappingUtilsTest {

    private ApplicationContext originalApplicationContext;

    @BeforeEach
    void setUp() throws Exception {
        originalApplicationContext = ApplicationContextUtil.getApplicationContext();
        resetRequestMappings();
    }

    @AfterEach
    void tearDown() throws Exception {
        new ApplicationContextUtil().setApplicationContext(originalApplicationContext);
        resetRequestMappings();
    }

    @Test
    void retriesInitializationAfterAControllerScanFailure() {
        new ApplicationContextUtil().setApplicationContext(failingApplicationContext());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> RequestMappingUtils.getRequestMappingInfo("/api/test/value", "GET"));
        assertEquals("test controller scan failure", failure.getMessage());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TestController.class);
            context.refresh();
            new ApplicationContextUtil().setApplicationContext(context);

            RequestMappingInfo mapping = RequestMappingUtils.getRequestMappingInfo("/api/test/value", "GET");

            assertNotNull(mapping);
            assertEquals(TestController.class, mapping.getController());
            assertEquals("value", mapping.getMethod());
            assertEquals(Collections.singletonList("GET"), mapping.getRequestMethods());
        }
    }

    private ApplicationContext failingApplicationContext() {
        return (ApplicationContext) Proxy.newProxyInstance(
                ApplicationContext.class.getClassLoader(),
                new Class<?>[] {ApplicationContext.class},
                (proxy, method, args) -> {
                    if ("getBeansWithAnnotation".equals(method.getName())) {
                        throw new IllegalStateException("test controller scan failure");
                    }
                    if ("toString".equals(method.getName())) {
                        return "failingApplicationContext";
                    }
                    return null;
                });
    }

    @Test
    void initializesWhenControllerDeclaresNoRequestMappingPath() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(BareController.class);
            context.refresh();
            new ApplicationContextUtil().setApplicationContext(context);

            RequestMappingInfo mapping = RequestMappingUtils.getRequestMappingInfo("/bare", "GET");

            assertNotNull(mapping);
            assertEquals(BareController.class, mapping.getController());
            assertEquals("bare", mapping.getMethod());
        }
    }

    private void resetRequestMappings() throws Exception {
        Field initialized = RequestMappingUtils.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.setBoolean(null, false);

        Field mappings = RequestMappingUtils.class.getDeclaredField("requestMappingInfoMap");
        mappings.setAccessible(true);
        mappings.set(null, Collections.emptyMap());
    }

    @RestController
    @RequestMapping("/api/test")
    static class TestController {

        @GetMapping("/value")
        public String value() {
            return "ok";
        }
    }

    @RestController
    @RequestMapping
    static class BareController {

        @GetMapping("/bare")
        public String bare() {
            return "ok";
        }
    }
}

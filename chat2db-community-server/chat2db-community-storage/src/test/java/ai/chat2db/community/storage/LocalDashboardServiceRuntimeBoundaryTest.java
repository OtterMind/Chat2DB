package ai.chat2db.community.storage;

import ai.chat2db.community.domain.api.service.dashboard.IDashboardService;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbDlTemplateService;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.tools.annotation.LocalPersistenceRuntimeOnly;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDashboardServiceRuntimeBoundaryTest {

    @Test
    void pageBeyondIntegerOffsetRangeIsEmpty() {
        LocalDashboardService service = new LocalDashboardService(
                proxy(IDbConnectionContextService.class),
                proxy(IDbDlTemplateService.class),
                proxy(IOpsSqlOperationLogService.class));

        var page = service.listDashboards(Integer.MAX_VALUE, 2, null);

        assertTrue(page.getData().isEmpty());
        assertEquals(Integer.MAX_VALUE, page.getPageNo());
        assertEquals(2, page.getPageSize());
        assertFalse(page.getHasNextPage());
    }

    @Test
    void localDashboardIsAvailableToCommunityAndLocalEditions() {
        assertTrue(LocalDashboardService.class.isAnnotationPresent(LocalPersistenceRuntimeOnly.class));

        ConditionalOnExpression condition = LocalPersistenceRuntimeOnly.class
                .getAnnotation(ConditionalOnExpression.class);
        assertEquals("'${chat2db.runtime.mode:pro}'.equalsIgnoreCase('community')"
                + " || '${chat2db.runtime.mode:pro}'.equalsIgnoreCase('local')", condition.value());
    }

    @Test
    void springLoadsLocalDashboardForLocalAndCommunityButNotPro() {
        for (String runtimeMode : new String[]{"community", "local"}) {
            try (AnnotationConfigApplicationContext context = context(runtimeMode, true)) {
                assertInstanceOf(LocalDashboardService.class, context.getBean(IDashboardService.class));
            }
        }

        try (AnnotationConfigApplicationContext context = context("pro", false)) {
            assertTrue(context.getBeansOfType(IDashboardService.class).isEmpty());
        }
    }

    private static AnnotationConfigApplicationContext context(String runtimeMode, boolean registerDependencies) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "test-runtime",
                Map.of("chat2db.runtime.mode", runtimeMode)));
        if (registerDependencies) {
            context.registerBean(IDbConnectionContextService.class,
                    () -> proxy(IDbConnectionContextService.class));
            context.registerBean(IDbDlTemplateService.class,
                    () -> proxy(IDbDlTemplateService.class));
            context.registerBean(IOpsSqlOperationLogService.class,
                    () -> proxy(IOpsSqlOperationLogService.class));
        }
        context.register(LocalDashboardService.class);
        context.refresh();
        return context;
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> null));
    }
}

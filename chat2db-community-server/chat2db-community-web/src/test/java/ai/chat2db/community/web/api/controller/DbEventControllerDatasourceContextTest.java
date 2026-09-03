package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbEventControllerDatasourceContextTest {

    @Test
    void eventLifecycleEndpointsAcceptDatasourceAwareRequests() {
        assertDatasourceAwareRequest("list");
        assertDatasourceAwareRequest("schedulerStatus");
        assertDatasourceAwareRequest("detail");
        assertDatasourceAwareRequest("dropSql");
        assertDatasourceAwareRequest("enabledSql");
    }

    private static void assertDatasourceAwareRequest(String methodName) {
        Method method = Arrays.stream(DbEventController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();

        assertEquals(1, method.getParameterCount(), methodName + " must accept one request parameter");
        assertTrue(DataSourceBaseRequest.class.isAssignableFrom(method.getParameterTypes()[0]),
                methodName + " must expose datasource context to ConnectionInfoAspect");
    }
}

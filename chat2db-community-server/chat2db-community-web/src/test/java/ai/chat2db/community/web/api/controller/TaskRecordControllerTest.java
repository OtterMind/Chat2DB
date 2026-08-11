package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.request.task.TaskRecordStopRequest;
import ai.chat2db.community.domain.api.service.task.ITaskRecordService;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRecordControllerTest {

    @Test
    void stopUsesPostBodyAndDelegatesTaskId() throws Exception {
        AtomicReference<Long> stoppedTaskId = new AtomicReference<>();
        ITaskRecordService taskRecordService = (ITaskRecordService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ITaskRecordService.class},
                (proxy, method, args) -> {
                    if ("stopTask".equals(method.getName())) {
                        stoppedTaskId.set((Long) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        TaskRecordController controller = new TaskRecordController(taskRecordService, null, null);
        TaskRecordStopRequest request = new TaskRecordStopRequest();
        request.setId(42L);

        controller.stop(request);

        assertEquals(42L, stoppedTaskId.get());

        Method stop = TaskRecordController.class.getMethod("stop", TaskRecordStopRequest.class);
        PostMapping postMapping = stop.getAnnotation(PostMapping.class);
        assertNotNull(postMapping);
        assertArrayEquals(new String[] {"/stop"}, postMapping.value());
        assertNull(stop.getAnnotation(GetMapping.class));
        assertTrue(stop.getParameters()[0].isAnnotationPresent(RequestBody.class));
        assertTrue(stop.getParameters()[0].isAnnotationPresent(Valid.class));
    }
}

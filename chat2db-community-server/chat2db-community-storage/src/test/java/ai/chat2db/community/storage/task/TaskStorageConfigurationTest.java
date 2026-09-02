package ai.chat2db.community.storage.task;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskEventLevel;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.storage.TestHome;
import ai.chat2db.community.storage.large.FileTaskStorage;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Bean selection for {@link TaskStorageConfiguration}: the rollback switch must replace the storage
 * implementation, never run two of them side by side.
 */
class TaskStorageConfigurationTest {

    static {
        // The H2 bean resolves its file from user.home, so a fresh home keeps consecutive runs from
        // reopening the previous run's database file.
        TestHome.init();
    }

    @Test
    void h2IsSelectedByDefaultAndExplicitly() {
        assertH2Selected(context(Map.of()));
        assertH2Selected(context(Map.of(TaskStorageConfiguration.STORAGE_PROPERTY, "h2")));
    }

    @Test
    void filePropertySelectsTheLegacyStorage() {
        try (AnnotationConfigApplicationContext context =
                context(Map.of(TaskStorageConfiguration.STORAGE_PROPERTY, "file"))) {
            assertEquals(1, context.getBeansOfType(TaskStorage.class).size());
            assertInstanceOf(FileTaskStorage.class, context.getBean(TaskStorage.class));
        }
    }

    @Test
    void unknownStorageValueKeepsH2BecauseOnlyFileOptsOut() {
        assertH2Selected(context(Map.of(TaskStorageConfiguration.STORAGE_PROPERTY, "postgres")));
    }

    @Test
    void fileSwitchIsCaseInsensitive() {
        try (AnnotationConfigApplicationContext context =
                context(Map.of(TaskStorageConfiguration.STORAGE_PROPERTY, "FILE"))) {
            assertEquals(1, context.getBeansOfType(TaskStorage.class).size());
            assertInstanceOf(FileTaskStorage.class, context.getBean(TaskStorage.class));
        }
    }

    @Test
    void closingTheContextShutsTheH2StorageAndReopeningReadsTheTaskBack() {
        Long taskId;
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            taskId = storage(context).create(task("wired"), event()).getId();
        }

        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            TaskStorage storage = storage(context);
            assertEquals("wired", storage.get(taskId).orElseThrow().getName());
            assertEquals(List.of(TaskEventCode.TASK_CREATED.name()),
                    storage.listEvents(taskId, 0L, 10).stream().map(TaskEvent::getCode).toList());
        }
    }

    @Test
    void h2BeanDeclaresAnExplicitDestroyMethodSoTheDatabaseFileIsReleased() {
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            assertEquals("close", context.getBeanDefinition("h2TaskStorage").getDestroyMethodName());
        }
    }

    private static void assertH2Selected(AnnotationConfigApplicationContext context) {
        try (context) {
            assertEquals(1, context.getBeansOfType(TaskStorage.class).size());
            assertInstanceOf(H2TaskStorage.class, context.getBean(TaskStorage.class));
        }
    }

    private static TaskStorage storage(AnnotationConfigApplicationContext context) {
        return context.getBean(TaskStorage.class);
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (!properties.isEmpty()) {
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test-task-storage", properties));
        }
        context.register(TaskStorageConfiguration.class);
        context.refresh();
        return context;
    }

    private static TaskEvent event() {
        return TaskEvent.builder()
                .level(TaskEventLevel.INFO.name())
                .code(TaskEventCode.TASK_CREATED.name())
                .message(TaskEventCode.TASK_CREATED.name())
                .build();
    }

    private static Task task(String name) {
        return Task.builder()
                .type("QUERY_RESULT_EXPORT")
                .name(name)
                .build();
    }
}

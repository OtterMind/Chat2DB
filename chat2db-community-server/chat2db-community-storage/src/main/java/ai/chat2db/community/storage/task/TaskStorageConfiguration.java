package ai.chat2db.community.storage.task;

import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.storage.large.FileTaskStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the task storage implementation. {@code chat2db.task.storage=h2} is the default;
 * {@code -Dchat2db.task.storage=file} is the rollback switch that keeps the previous layout usable
 * for one release after the H2 migration has run.
 */
@Configuration
public class TaskStorageConfiguration {

    static final String STORAGE_PROPERTY = "chat2db.task.storage";

    /**
     * SpEL instead of {@code @ConditionalOnProperty} so that only an exact opt-out selects the file
     * layout: an unknown or mistyped value must not leave the application without any task storage.
     */
    private static final String FILE_CONDITION =
            "'${" + STORAGE_PROPERTY + ":h2}'.equalsIgnoreCase('file')";

    @Bean
    @ConditionalOnExpression(FILE_CONDITION)
    public TaskStorage fileTaskStorage() {
        return new FileTaskStorage();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!" + FILE_CONDITION)
    public TaskStorage h2TaskStorage() {
        String storageBasePath = TaskDatabase.defaultStorageBasePath();
        TaskDatabase database = new TaskDatabase(storageBasePath);
        new TaskStorageMigrator(database, storageBasePath).migrateIfRequired();
        return new H2TaskStorage(database);
    }
}

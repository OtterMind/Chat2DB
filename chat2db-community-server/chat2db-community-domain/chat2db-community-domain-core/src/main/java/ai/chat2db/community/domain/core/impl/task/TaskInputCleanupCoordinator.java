package ai.chat2db.community.domain.core.impl.task;

@FunctionalInterface
interface TaskInputCleanupCoordinator {

    boolean cleanupTaskInput(Long taskId);
}

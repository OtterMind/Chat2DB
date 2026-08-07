package ai.chat2db.community.domain.api.service.task;

@FunctionalInterface
public interface TaskCancelable {

    void cancel() throws Exception;
}

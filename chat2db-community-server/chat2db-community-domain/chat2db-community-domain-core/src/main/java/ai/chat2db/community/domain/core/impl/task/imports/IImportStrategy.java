package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;

public interface IImportStrategy {


    void run(ImportTaskSpec spec, TaskExecutionContext context);
}

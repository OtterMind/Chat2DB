package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;

import java.io.File;

public interface IExportStrategy {


    void run(ExportTaskSpec spec, TaskExecutionContext context, File outputFile);

}

package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;

public interface IImportTaskSubmissionService {

    Long submit(ImportTaskSpec spec, String stagedFileId);
}

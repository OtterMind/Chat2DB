package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.api.service.task.IImportTaskSubmissionService;
import ai.chat2db.community.domain.api.service.task.TaskService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class ImportTaskSubmissionServiceImpl implements IImportTaskSubmissionService {

    private final TaskService taskService;

    private final IImportFileStagingService importFileStagingService;

    public ImportTaskSubmissionServiceImpl(TaskService taskService,
            IImportFileStagingService importFileStagingService) {
        this.taskService = taskService;
        this.importFileStagingService = importFileStagingService;
    }

    @Override
    public Long submit(ImportTaskSpec spec, String stagedFileId) {
        if (StringUtils.isBlank(stagedFileId)) {
            return taskService.submitImport(spec);
        }

        File stagedFile = importFileStagingService.resolve(stagedFileId);
        importFileStagingService.claimForTask(stagedFileId);
        spec.setSourceFile(stagedFile.getAbsolutePath());
        spec.setImportFileId(stagedFileId);
        try {
            return taskService.submitImport(spec);
        } catch (RuntimeException e) {
            importFileStagingService.release(stagedFileId);
            throw e;
        }
    }
}

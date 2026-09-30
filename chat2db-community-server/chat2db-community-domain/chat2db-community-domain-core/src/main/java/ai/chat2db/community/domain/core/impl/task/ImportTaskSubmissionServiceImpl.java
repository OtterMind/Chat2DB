package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.api.service.task.IImportTaskSubmissionService;
import ai.chat2db.community.domain.api.service.task.TaskService;
import com.google.common.util.concurrent.Striped;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;

@Service
public class ImportTaskSubmissionServiceImpl implements IImportTaskSubmissionService {

    private static final int MAX_CLIENT_SUBMISSION_ID_LENGTH = 128;

    private final Striped<Lock> submissionLocks = Striped.lazyWeakLock(64);

    private final TaskService taskService;

    private final IImportFileStagingService importFileStagingService;

    public ImportTaskSubmissionServiceImpl(TaskService taskService,
            IImportFileStagingService importFileStagingService) {
        this.taskService = taskService;
        this.importFileStagingService = importFileStagingService;
    }

    @Override
    public Long submit(ImportTaskSpec spec, String stagedFileId) {
        if (spec == null) {
            throw new IllegalArgumentException("Import task spec is required");
        }
        validateTopLevelStagedFileIds(spec, stagedFileId);
        String clientSubmissionId = StringUtils.trimToNull(spec.getClientSubmissionId());
        if (clientSubmissionId == null) {
            return submitNew(spec, stagedFileId);
        }
        if (clientSubmissionId.length() > MAX_CLIENT_SUBMISSION_ID_LENGTH) {
            throw new IllegalArgumentException("Client submission ID exceeds 128 characters");
        }
        spec.setClientSubmissionId(clientSubmissionId);
        String clientSubmissionFingerprint = ImportTaskSubmissionFingerprint.create(spec, stagedFileId);
        spec.setClientSubmissionFingerprint(clientSubmissionFingerprint);
        Lock lock = submissionLocks.get(clientSubmissionId);
        lock.lock();
        try {
            Long existingTaskId = taskService.findImportTaskId(clientSubmissionId, clientSubmissionFingerprint);
            return existingTaskId == null ? submitNew(spec, stagedFileId) : existingTaskId;
        } finally {
            lock.unlock();
        }
    }

    private Long submitNew(ImportTaskSpec spec, String stagedFileId) {
        String legacyFileId = StringUtils.firstNonBlank(stagedFileId, spec.getImportFileId());
        Set<String> fileIds = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(legacyFileId)) {
            fileIds.add(legacyFileId);
        }
        if (spec.getTableSources() != null) {
            spec.getTableSources().stream().filter(java.util.Objects::nonNull)
                    .map(ImportTableSource::getImportFileId).filter(StringUtils::isNotBlank)
                    .forEach(fileIds::add);
        }
        if (fileIds.isEmpty()) {
            return taskService.submitImport(spec);
        }

        Map<String, File> resolved = new LinkedHashMap<>();
        List<String> claimed = new ArrayList<>();
        try {
            for (String fileId : fileIds) {
                resolved.put(fileId, importFileStagingService.resolve(fileId));
            }
            for (String fileId : fileIds) {
                importFileStagingService.claimForTask(fileId);
                claimed.add(fileId);
            }
            if (StringUtils.isNotBlank(legacyFileId)) {
                spec.setSourceFile(resolved.get(legacyFileId).getAbsolutePath());
                spec.setImportFileId(legacyFileId);
            }
            if (spec.getTableSources() != null) {
                for (ImportTableSource source : spec.getTableSources()) {
                    if (source != null && StringUtils.isNotBlank(source.getImportFileId())) {
                        source.setSourceFile(resolved.get(source.getImportFileId()).getAbsolutePath());
                    }
                }
            }
            return taskService.submitImport(spec);
        } catch (RuntimeException e) {
            releaseClaims(claimed, e);
            throw e;
        }
    }

    private void validateTopLevelStagedFileIds(ImportTaskSpec spec, String stagedFileId) {
        if (StringUtils.isNotBlank(stagedFileId) && StringUtils.isNotBlank(spec.getImportFileId())
                && !StringUtils.equals(stagedFileId, spec.getImportFileId())) {
            throw new IllegalArgumentException("Top-level staged import file IDs do not match");
        }
    }

    private void releaseClaims(List<String> claimed, RuntimeException failure) {
        for (int index = claimed.size() - 1; index >= 0; index--) {
            try {
                importFileStagingService.release(claimed.get(index));
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
        }
    }
}

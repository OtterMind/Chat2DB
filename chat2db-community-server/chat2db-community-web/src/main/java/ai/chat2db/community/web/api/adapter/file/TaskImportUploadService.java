package ai.chat2db.community.web.api.adapter.file;

import org.springframework.web.multipart.MultipartFile;

public interface TaskImportUploadService {

    StagedTaskInput stage(MultipartFile file);

    boolean cleanup(StagedTaskInput input);

    record StagedTaskInput(String sourceFile, String cleanupToken) {
    }
}

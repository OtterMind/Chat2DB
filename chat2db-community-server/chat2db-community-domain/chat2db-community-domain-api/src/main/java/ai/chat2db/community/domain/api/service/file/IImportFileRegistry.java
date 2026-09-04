package ai.chat2db.community.domain.api.service.file;

import java.io.File;

public interface IImportFileRegistry {
    long MAX_IMPORT_FILE_SIZE_BYTES = 50L * 1024L * 1024L;

    String register(File file, String originalFileName);

    File resolve(String fileId);

    void claim(String fileId);

    void release(String fileId);
}

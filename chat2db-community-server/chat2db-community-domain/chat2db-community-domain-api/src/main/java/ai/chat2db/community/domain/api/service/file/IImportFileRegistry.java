package ai.chat2db.community.domain.api.service.file;

import java.io.File;

public interface IImportFileRegistry {
    String register(File file, String originalFileName);

    File resolve(String fileId);

    void claim(String fileId);

    void release(String fileId);
}

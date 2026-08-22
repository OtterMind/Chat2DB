package ai.chat2db.community.web.api.adapter.db;

import ai.chat2db.community.domain.api.service.db.IDbJdbcDriverUploadService;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class MultipartJdbcDriverUploadAdapter implements IDbJdbcDriverUploadService<MultipartFile[]> {

    @Override
    public List<String> upload(MultipartFile[] files) throws IOException {
        List<String> uploadedFiles = new ArrayList<>();
        File driverDirectory = JdbcDriverConstants.createDriverLibDirectory();
        for (MultipartFile file : files) {
            String originalFilename = FilenameUtils.getName(file.getOriginalFilename());
            file.transferTo(new File(driverDirectory, originalFilename));
            uploadedFiles.add(originalFilename);
        }
        return uploadedFiles;
    }
}

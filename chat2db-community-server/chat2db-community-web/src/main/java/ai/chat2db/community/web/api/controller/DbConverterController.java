package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.enums.file.ConfigFileTypeEnum;
import ai.chat2db.community.domain.api.service.file.IUploadFileService;
import ai.chat2db.community.domain.api.service.task.ITaskNcxImportService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.converter.ncx.IConverterWebConverter;
import ai.chat2db.community.web.api.model.request.ncx.DatagripUploadRequest;
import ai.chat2db.community.web.api.model.response.ncx.UploadResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * DbConverterController
 *
 */

/**
 * Handles SQL and file conversion endpoints.
 */
@RequestMapping("/api/converter")
@RestController
@Slf4j
public class DbConverterController {

    private final ITaskNcxImportService ncxImportService;
    private final IConverterWebConverter converterWebConverter;
    private final IUploadFileService<MultipartFile> uploadFileService;

    public DbConverterController(ITaskNcxImportService ncxImportService,
            IConverterWebConverter converterWebConverter,
            IUploadFileService<MultipartFile> uploadFileService) {
        this.ncxImportService = ncxImportService;
        this.converterWebConverter = converterWebConverter;
        this.uploadFileService = uploadFileService;
    }

    /**
     * Uploads an NCX file for conversion.
     * <p>
     * Endpoint: {@code POST /api/converter/ncx/upload}.
     *
     * @param file uploaded file for the request.
     * @return data result containing upload response.
     */
    @SneakyThrows
    @PostMapping("/ncx/upload")
    public DataResult<UploadResponse> ncxUploadFile(@RequestParam("file") MultipartFile file) {
        log.info("Start uploading NCX file");
        File temp = uploadFileService.transferToTempFile(file, ConfigFileTypeEnum.NCX);
        try {
            return DataResult.of(converterWebConverter.ncxImportResult2response(ncxImportService.ncxUploadFile(temp)));
        } finally {
            deleteTempFile(temp);
        }
    }

    /**
     * Uploads conversion files.
     * <p>
     * Endpoint: {@code POST/GET /api/converter/upload}.
     *
     * @param file uploaded file for the request.
     * @return data result containing upload response.
     */
    @RequestMapping(value = "/upload",method = {RequestMethod.POST,RequestMethod.GET})
    public DataResult<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        ConfigFileTypeEnum fileType = ConfigFileTypeEnum.fromExtension(uploadFileService.extension(file));
        File temp = uploadFileService.transferToTempFileOrThrow(file);
        try {
            return DataResult.of(converterWebConverter.ncxImportResult2response(ncxImportService.uploadFile(temp, fileType)));
        } finally {
            deleteTempFile(temp);
        }
    }

    /**
     * Uploads an EDBP file for conversion.
     * <p>
     * Endpoint: {@code POST /api/converter/dbp/upload}.
     *
     * @param file uploaded file for the request.
     * @param masterPassword optional DBeaver master password, used to decrypt connection credentials
     *                       when the default local key does not match. May be null. Request-scoped:
     *                       it is handed to the import service and is never stored or logged. It
     *                       travels in the request form like the connection passwords the datasource
     *                       endpoints already accept, so it relies on the loopback-only deployment
     *                       boundary documented in SECURITY.md.
     * @return data result containing upload response.
     */
    @SneakyThrows
    @PostMapping("/dbp/upload")
    public DataResult<UploadResponse> edbpUploadFile(@RequestParam("file") MultipartFile file,
                                                     @RequestParam(value = "masterPassword", required = false) String masterPassword) {
        File temp = uploadFileService.transferToTempFile(file, ConfigFileTypeEnum.DBP);
        try {
            return DataResult.of(converterWebConverter.ncxImportResult2response(ncxImportService.dbpUploadFile(temp, masterPassword)));
        } finally {
            deleteTempFile(temp);
        }
    }

    /**
     * Uploads a DataGrip file for conversion.
     * <p>
     * Endpoint: {@code POST /api/converter/datagrip/upload}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing upload response.
     */
    @SneakyThrows
    @PostMapping("/datagrip/upload")
    public DataResult<UploadResponse> datagripUploadFile(@RequestBody DatagripUploadRequest request) {
        return DataResult.of(converterWebConverter.ncxImportResult2response(ncxImportService.datagripUploadFile(request.getText())));
    }


    /**
     * Uploads a Chat2DB file for conversion.
     * <p>
     * Endpoint: {@code POST /api/converter/chat2db/upload}.
     *
     * @param file uploaded file for the request.
     * @return data result containing upload response.
     */
    @SneakyThrows
    @PostMapping("/chat2db/upload")
    public DataResult<UploadResponse> chat2dbUploadFile(@RequestParam("file") MultipartFile file) {
        File temp = uploadFileService.transferToTempFile(file, ConfigFileTypeEnum.JSON);
        try {
            return DataResult.of(converterWebConverter.ncxImportResult2response(ncxImportService.chat2dbUploadFile(temp)));
        } finally {
            deleteTempFile(temp);
        }
    }

    /**
     * Best-effort cleanup of a temp file produced by transferToTempFile.
     * Never throws so it does not mask the primary result.
     */
    private void deleteTempFile(File temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp.toPath());
        } catch (IOException | SecurityException e) {
            log.warn("Failed to delete temp file: {}", temp.getAbsolutePath(), e);
        }
    }

}

package ai.chat2db.community.domain.api.service.task;

import java.io.File;

import ai.chat2db.community.domain.api.enums.file.ConfigFileTypeEnum;
import ai.chat2db.community.domain.api.model.ncx.NcxImportResponse;

public interface ITaskNcxImportService {

    /**
     * Imports datasource definitions from an NCX file.
     *
     * @param file uploaded NCX file.
     * @return import response.
     */
    NcxImportResponse ncxUploadFile(File file);

    /**
     * Imports datasource definitions from a DBP file.
     *
     * @param file uploaded DBP file.
     * @param masterPassword optional DBeaver master password, used to decrypt connection credentials
     *                       when the default local key does not match (e.g. master password is set in DBeaver).
     *                       May be null.
     * @return import response.
     */
    NcxImportResponse dbpUploadFile(File file, String masterPassword);

    /**
     * Imports datasource definitions from DataGrip export text.
     *
     * @param text DataGrip export text.
     * @return import response.
     */
    NcxImportResponse datagripUploadFile(String text);

    /**
     * Imports datasource definitions from a Chat2DB export file.
     *
     * @param file uploaded Chat2DB export file.
     * @return import response.
     */
    NcxImportResponse chat2dbUploadFile(File file);

    /**
     * Imports datasource definitions by uploaded config file type.
     *
     * @param file uploaded config file.
     * @param fileType config file type.
     * @return import response.
     */
    NcxImportResponse uploadFile(File file, ConfigFileTypeEnum fileType);
}

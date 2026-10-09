package ai.chat2db.community.web.api.adapter.db.cell;

import ai.chat2db.community.domain.api.model.db.CellValueDownload;
import ai.chat2db.community.domain.api.model.db.LargeValueToken;
import ai.chat2db.community.domain.api.model.request.db.DbCellValueChunkReadRequest;
import ai.chat2db.community.domain.api.model.request.db.DbCellValueTokenReadRequest;
import ai.chat2db.community.domain.api.service.db.IDbLargeCellValueTransferService;
import ai.chat2db.community.domain.api.service.db.IDbLargeValueTokenService;
import ai.chat2db.community.web.api.converter.db.CellValueConverter;
import ai.chat2db.community.web.api.model.response.db.cell.CellValueChunkResponse;
import ai.chat2db.community.web.api.util.DownloadUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

@Component
public class CellValueWebAdapter implements IDbLargeCellValueTransferService<HttpServletResponse, CellValueChunkResponse> {

    private static final int STREAM_BUFFER_SIZE = 8192;

    private final IDbLargeValueTokenService largeValueTokenService;
    private final ai.chat2db.community.domain.api.service.db.IDbCellValueService domainCellValueService;
    private final CellValueConverter cellValueConverter;

    public CellValueWebAdapter(IDbLargeValueTokenService largeValueTokenService,
                            ai.chat2db.community.domain.api.service.db.IDbCellValueService domainCellValueService,
                            CellValueConverter cellValueConverter) {
        this.largeValueTokenService = largeValueTokenService;
        this.domainCellValueService = domainCellValueService;
        this.cellValueConverter = cellValueConverter;
    }

    @Override
    public CellValueChunkResponse readByToken(DbCellValueTokenReadRequest dbCellValueTokenReadRequest) {
        LargeValueToken token = largeValueTokenService.requireValid(dbCellValueTokenReadRequest.getLargeValueId());
        DbCellValueChunkReadRequest readCellValueChunkRequest = new DbCellValueChunkReadRequest();
        readCellValueChunkRequest.setReference(cellValueConverter.token2reference(token));
        readCellValueChunkRequest.setOffset(dbCellValueTokenReadRequest.getOffset());
        readCellValueChunkRequest.setLimit(dbCellValueTokenReadRequest.getLimit());
        readCellValueChunkRequest.setFormat(dbCellValueTokenReadRequest.getFormat());
        return cellValueConverter.chunk2response(domainCellValueService.readChunk(readCellValueChunkRequest));
    }

    public void download(String largeValueId, String format, HttpServletResponse response) {
        LargeValueToken token = largeValueTokenService.requireValid(largeValueId);
        CellValueDownload payload = domainCellValueService.prepareDownload(cellValueConverter.token2reference(token),
                format);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + UriUtils.encode(payload.getFileName(), StandardCharsets.UTF_8));
        response.setContentType(payload.getContentType());
        try {
            stream(payload.getInputStream(), response.getOutputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String downloadToLocalFile(String largeValueId, String format) {
        LargeValueToken token = largeValueTokenService.requireValid(largeValueId);
        CellValueDownload payload = domainCellValueService.prepareDownload(cellValueConverter.token2reference(token),
                format);
        File file = DownloadUtil.createDownloadFile(fileNamePrefix(payload.getFileName()),
                fileNameSuffix(payload.getFileName()), true);
        try (InputStream inputStream = payload.getInputStream();
             OutputStream outputStream = Files.newOutputStream(file.toPath())) {
            stream(inputStream, outputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return file.getAbsolutePath();
    }


    private void stream(InputStream inputStream, OutputStream outputStream) throws IOException {
        try (inputStream) {
            byte[] buffer = new byte[STREAM_BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }
    }

    private String fileNamePrefix(String fileName) {
        int suffixStart = fileName.lastIndexOf('.');
        String prefix = suffixStart > 0 ? fileName.substring(0, suffixStart) : fileName;
        return org.apache.commons.lang3.StringUtils.defaultIfBlank(prefix, "cell-value");
    }

    private String fileNameSuffix(String fileName) {
        int suffixStart = fileName.lastIndexOf('.');
        return suffixStart >= 0 ? fileName.substring(suffixStart) : ".bin";
    }

}

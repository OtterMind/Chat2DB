package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbLargeCellValueTransferService;
import ai.chat2db.community.domain.api.service.db.IDbLargeValueTokenService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.db.CellValueConverter;
import ai.chat2db.community.web.api.model.request.db.cell.CellValueDownloadRequest;
import ai.chat2db.community.web.api.model.request.db.cell.CellValueReadRequest;
import ai.chat2db.community.web.api.model.request.db.cell.CellValueReleaseRequest;
import ai.chat2db.community.web.api.model.response.db.cell.CellValueChunkResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Streams and reads large relational cell values.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/cell")
@RestController
public class DbCellValueController {

    private final IDbLargeCellValueTransferService<HttpServletResponse, CellValueChunkResponse> cellValueTransferService;
    private final IDbLargeValueTokenService largeValueTokenService;
    private final CellValueConverter cellValueConverter;

    public DbCellValueController(
            IDbLargeCellValueTransferService<HttpServletResponse, CellValueChunkResponse> cellValueTransferService,
            IDbLargeValueTokenService largeValueTokenService,
            CellValueConverter cellValueConverter) {
        this.cellValueTransferService = cellValueTransferService;
        this.largeValueTokenService = largeValueTokenService;
        this.cellValueConverter = cellValueConverter;
    }

    /**
     * Releases large-value handles whose result set the client has discarded.
     * <p>
     * Endpoint: {@code POST /api/rdb/cell/release}.
     *
     * @param request handles to release.
     * @return empty data result.
     */
    @PostMapping("/release")
    public DataResult<Void> release(@RequestBody CellValueReleaseRequest request) {
        largeValueTokenService.releaseTokens(request.getLargeValueIds());
        return DataResult.of(null);
    }

    /**
     * Handles value for relational cell values.
     * <p>
     * Endpoint: {@code POST /api/rdb/cell/value}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing cell value chunk response.
     */
    @PostMapping("/value")
    public DataResult<CellValueChunkResponse> value(@RequestBody CellValueReadRequest request) {
        return DataResult.of(cellValueTransferService.readByToken(cellValueConverter.readRequest2param(request)));
    }

    /**
     * Downloads relational cell values.
     * <p>
     * Endpoint: {@code GET /api/rdb/cell/download}.
     *
     * @param request request payload or query parameters for the operation.
     * @param response HTTP response used to write the download stream.
     */
    @GetMapping("/download")
    public void download(CellValueDownloadRequest request, HttpServletResponse response) {
        cellValueTransferService.download(request.getLargeValueId(), request.getFormat(), response);
    }

    /**
     * Creates a download path for a large cell value.
     * <p>
     * Endpoint: {@code POST /api/rdb/cell/download_path}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing string.
     */
    @PostMapping("/download_path")
    public DataResult<String> downloadPath(@RequestBody CellValueDownloadRequest request) {
        return DataResult.of(cellValueTransferService.downloadToLocalFile(request.getLargeValueId(), request.getFormat()));
    }
}

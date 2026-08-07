package ai.chat2db.community.web.api.controller;

import jakarta.validation.Valid;

import ai.chat2db.community.domain.api.service.db.IDbJdbcDriverService;
import ai.chat2db.community.domain.api.service.db.IDbJdbcDriverUploadService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.web.api.converter.driver.JdbcDriverConverter;
import ai.chat2db.community.web.api.model.request.driver.JdbcDriverRequest;
import ai.chat2db.community.web.api.model.response.driver.DriverResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Manages JDBC driver discovery, upload, download, and removal endpoints.
 */
@RequestMapping("/api/jdbc/driver")
@RestController
@Slf4j
public class DbJdbcDriverController {

    @Autowired
    private JdbcDriverConverter jdbcDriverConverter;

    @Autowired
    private IDbJdbcDriverService jdbcDriverService;

    private final IDbJdbcDriverUploadService<MultipartFile[]> jdbcDriverUploadService;

    public DbJdbcDriverController(IDbJdbcDriverUploadService<MultipartFile[]> jdbcDriverUploadService) {
        this.jdbcDriverUploadService = jdbcDriverUploadService;
    }

    /**
     * Lists JDBC drivers.
     * <p>
     * Endpoint: {@code GET /api/jdbc/driver/list}.
     *
     * @param dbType database type value.
     * @return data result containing driver response.
     */
    @GetMapping("/list")
    public DataResult<DriverResponse> list(@RequestParam String dbType) {
        return DataResult.of(jdbcDriverConverter.driverConfigView2response(
                jdbcDriverService.queryDriverConfigView(dbType)));
    }


    /**
     * Downloads JDBC drivers.
     * <p>
     * Endpoint: {@code GET /api/jdbc/driver/download}.
     *
     * @param dbType database type value.
     * @return operation result for the request.
     */
    @GetMapping("/download")
    public ActionResult download(@RequestParam String dbType) {
        jdbcDriverService.downloadBuiltinDriversOrThrow(dbType);
        return ActionResult.isSuccess();
    }

    /**
     * Uploads JDBC drivers.
     * <p>
     * Endpoint: {@code POST /api/jdbc/driver/upload}.
     *
     * @param multipartFiles uploaded file for the request.
     * @return list result containing string.
     */
    @PostMapping("/upload")
    public ListResult<String> upload(@RequestParam MultipartFile[] multipartFiles) {
        return ListResult.of(jdbcDriverUploadService.uploadOrThrow(multipartFiles));
    }

    /**
     * Saves JDBC drivers.
     * <p>
     * Endpoint: {@code POST /api/jdbc/driver/save}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/save")
    public ActionResult save(@Valid @RequestBody JdbcDriverRequest request) {
        jdbcDriverService.saveCustomDriver(jdbcDriverConverter.saveRequest2driverConfig(request),
                request.getJdbcDriver());
        return ActionResult.isSuccess();
    }

    /**
     * Deletes JDBC drivers.
     * <p>
     * Endpoint: {@code DELETE /api/jdbc/driver/delete}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @DeleteMapping("/delete")
    public ActionResult delete(@Valid @RequestBody JdbcDriverRequest request) {
        jdbcDriverService.deleteCustomDriver(request.getDbType(), request.getJdbcDriver());
        return ActionResult.isSuccess();
    }

}

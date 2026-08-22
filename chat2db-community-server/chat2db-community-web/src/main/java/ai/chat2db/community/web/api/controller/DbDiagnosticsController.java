package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbDiagnosticsService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes database diagnostic endpoints.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/diagnostics")
@RestController
public class DbDiagnosticsController {

    @Autowired
    private IDbDiagnosticsService diagnosticsService;

    /**
     * Returns the raw InnoDB status output.
     * <p>
     * Endpoint: {@code POST /api/rdb/diagnostics/innodb_status}.
     *
     * @return data result containing the raw status text.
     */
    @PostMapping("/innodb_status")
    public DataResult<String> innodbStatus(@Valid @RequestBody DiagnosticsRequest request) {
        return DataResult.of(diagnosticsService.innodbStatus());
    }

    @Data
    public static class DiagnosticsRequest extends DataSourceBaseRequest {
    }
}

package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbDiagnosticsService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
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
     * Endpoint: {@code GET /api/rdb/diagnostics/innodb_status}.
     *
     * @return data result containing the raw status text.
     */
    @GetMapping("/innodb_status")
    public DataResult<String> innodbStatus() {
        return DataResult.of(diagnosticsService.innodbStatus());
    }
}

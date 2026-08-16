package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.config.SupportedDatabaseSummary;
import ai.chat2db.community.domain.api.service.db.IDbSupportedDatabaseService;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the supported-database inventory derived from the plugin registry.
 */
@RequestMapping("/api/database")
@RestController
public class DbSupportedDatabaseController {

    @Autowired
    private IDbSupportedDatabaseService supportedDatabaseService;

    /**
     * Lists all database types registered by plugins.
     * <p>
     * Endpoint: {@code GET /api/database/supported}.
     *
     * @return list result containing one summary per registered database type.
     */
    @GetMapping("/supported")
    public ListResult<SupportedDatabaseSummary> supported() {
        return ListResult.of(supportedDatabaseService.listSupportedDatabases());
    }
}

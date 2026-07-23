package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.spi.config.SupportedDatabaseRegistry;
import ai.chat2db.spi.config.SupportedDatabaseSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the supported-database inventory derived from the plugin registry.
 */
@RequestMapping("/api/database")
@RestController
public class DbSupportedDatabaseController {

    /**
     * Lists all database types registered by plugins.
     * <p>
     * Endpoint: {@code GET /api/database/supported}.
     *
     * @return list result containing one summary per registered database type.
     */
    @GetMapping("/supported")
    public ListResult<SupportedDatabaseSummary> supported() {
        return ListResult.of(SupportedDatabaseRegistry.listSupportedDatabases());
    }
}

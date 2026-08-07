package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.service.db.IDbCustomDatabaseService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manages database types defined by the user, for engines whose driver cannot be
 * redistributed and which have no built-in type to attach a driver to.
 * <p>
 * A type saved here is registered immediately, so it appears in
 * {@code /api/database/supported} and accepts connections without a restart. The
 * driver JAR itself is uploaded through {@code /api/jdbc/driver/upload}.
 */
@RequestMapping("/api/database/custom")
@RestController
public class DbCustomDatabaseController {

    @Autowired
    private IDbCustomDatabaseService customDatabaseService;

    /**
     * Lists every user-defined database type.
     * <p>
     * Endpoint: {@code GET /api/database/custom/list}.
     */
    @GetMapping("/list")
    public ListResult<DBConfig> list() {
        return ListResult.of(customDatabaseService.listCustomDatabases());
    }

    /**
     * Returns one user-defined database type.
     * <p>
     * Endpoint: {@code GET /api/database/custom/get}.
     */
    @GetMapping("/get")
    public DataResult<DBConfig> get(@RequestParam String dbType) {
        return DataResult.of(customDatabaseService.queryCustomDatabase(dbType));
    }

    /**
     * Adds or replaces a user-defined database type.
     * <p>
     * Endpoint: {@code POST /api/database/custom/save}.
     */
    @PostMapping("/save")
    public ActionResult save(@RequestBody DBConfig config) {
        customDatabaseService.saveCustomDatabase(config);
        return ActionResult.isSuccess();
    }

    /**
     * Removes a user-defined database type.
     * <p>
     * Endpoint: {@code DELETE /api/database/custom/delete}.
     */
    @DeleteMapping("/delete")
    public DataResult<Boolean> delete(@RequestParam String dbType) {
        return DataResult.of(customDatabaseService.deleteCustomDatabase(dbType));
    }
}

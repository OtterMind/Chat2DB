package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.lock.LockView;
import ai.chat2db.community.domain.api.service.db.IDbLockService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes data and metadata lock inspection with blocking chains (MYSQL-OPS-003).
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/lock")
@RestController
public class DbLockController {

    @Autowired
    private IDbLockService lockService;

    /**
     * Returns the current lock snapshot.
     * <p>
     * Endpoint: {@code GET /api/rdb/lock/view}.
     */
    @GetMapping("/view")
    public DataResult<LockView> view(@Valid DataSourceBaseRequest request) {
        return DataResult.of(lockService.lockView(request.getDataSourceId()));
    }
}

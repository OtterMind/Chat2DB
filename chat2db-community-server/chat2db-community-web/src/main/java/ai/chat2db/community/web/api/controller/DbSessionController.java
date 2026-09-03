package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.db.DbSessionKillResult;
import ai.chat2db.community.domain.api.service.db.IDbSessionService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Exposes database session inspection and termination endpoints.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/session")
@RestController
public class DbSessionController {

    @Autowired
    private IDbSessionService sessionService;

    /**
     * Lists active database sessions.
     * <p>
     * Endpoint: {@code POST /api/rdb/session/list}.
     *
     * @return data result containing a list of session maps.
     */
    @PostMapping("/list")
    public DataResult<List<Map<String, Object>>> list(@RequestBody @Valid SessionListRequest request) {
        return DataResult.of(sessionService.list());
    }

    /**
     * Terminates a database session or query.
     * <p>
     * Endpoint: {@code POST /api/rdb/session/kill}.
     *
     * @param request kill request containing connection ID and kill type.
     * @return operation result for the request.
     */
    @PostMapping("/kill")
    public DataResult<DbSessionKillResult> kill(@RequestBody @Valid KillRequest request) {
        return DataResult.of(sessionService.kill(request.getConnectionId(), request.getKillType()));
    }

    @Data
    public static class SessionListRequest extends DataSourceBaseRequest {
    }

    @Data
    public static class KillRequest extends SessionListRequest {
        @NotNull
        private Long connectionId;

        @NotBlank
        private String killType;
    }
}

package ai.chat2db.community.web.api.controller;

import jakarta.validation.Valid;

import ai.chat2db.community.domain.api.service.db.IDbDiffService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.diff.DiffConverter;
import ai.chat2db.community.web.api.model.request.db.StructureDiffRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes database structure diff endpoints.
 */
@Slf4j
@ConnectionInfoAspect
@RequestMapping("/api/diff")
@RestController
public class DbDiffController {

    @Autowired
    private IDbDiffService diffService;

    @Autowired
    private DiffConverter diffConverter;

    /**
     * Handles diff for database diffs.
     * <p>
     * Endpoint: {@code POST /api/diff/sql}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing string.
     */
    @PostMapping("/sql")
    public DataResult<String> diff(@Valid @RequestBody StructureDiffRequest  request) {
        return DataResult.of(diffService.diff(diffConverter.structureInfo2param(request.getSource()),
                diffConverter.structureInfo2param(request.getTarget())));
    }
}

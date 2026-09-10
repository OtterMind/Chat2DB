package ai.chat2db.community.web.api.controller;

import jakarta.validation.Valid;

import ai.chat2db.community.domain.api.model.er.ERModel;
import ai.chat2db.community.domain.api.service.db.IDbErService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.er.ErWebConverter;
import ai.chat2db.community.web.api.model.request.er.ERModelPositionSaveRequest;
import ai.chat2db.community.web.api.model.request.er.ERModelQueryRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes entity-relationship metadata endpoints.
 */
@ConnectionInfoAspect
@RequestMapping("/api/er")
@RestController
public class DbErController {

    @Autowired
    private IDbErService erService;

    @Autowired
    private ErWebConverter erWebConverter;

    /**
     * Handles ER for entity-relationship metadata.
     * <p>
     * Endpoint: {@code REQUEST /api/er/get_info}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing ER model.
     */
    @RequestMapping("/get_info")
    public DataResult<ERModel> er(ERModelQueryRequest request) {
        return DataResult.of(erService.getModelWithPosition(erWebConverter.request2param(request)));
    }

    /**
     * Saves position.
     * <p>
     * Endpoint: {@code POST /api/er/save_position}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/save_position")
    public ActionResult savePosition(@Valid @RequestBody ERModelPositionSaveRequest request) {
        erService.savePosition(erWebConverter.request2position(request));
        return ActionResult.isSuccess();
    }


}

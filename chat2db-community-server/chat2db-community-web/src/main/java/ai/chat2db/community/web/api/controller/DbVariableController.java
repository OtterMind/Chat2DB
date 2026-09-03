package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbVariableService;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceConsoleRequestInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Exposes MySQL variables and status inspection with guarded editing (MYSQL-OPS-004).
 * Status views never perform writes; only registered variables can produce a SET preview.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/variable")
@RestController
public class DbVariableController {

    @Autowired
    private IDbVariableService variableService;

    @Autowired
    private IDbConnectionContextService connectionContextService;

    /**
     * Lists variables or status counters.
     * <p>
     * Endpoint: {@code GET /api/rdb/variable/list?scope=GLOBAL&kind=VARIABLES}.
     */
    @GetMapping("/list")
    public DataResult<List<Map<String, Object>>> list(@Valid VariableListRequest request) {
        return DataResult.of(variableService.variables(request.getScope(), request.getKind()));
    }

    /**
     * Returns edit metadata for a variable, or null when it must stay read-only.
     * <p>
     * Endpoint: {@code GET /api/rdb/variable/editable?name=wait_timeout}.
     */
    @GetMapping("/editable")
    public DataResult<IDbVariableService.EditMeta> editable(@Valid VariableNameRequest request) {
        return DataResult.of(variableService.editable(request.getName()));
    }

    /**
     * Generates a SET statement preview for a registered variable.
     * <p>
     * Endpoint: {@code POST /api/rdb/variable/set_preview}.
     */
    @RequestMapping(value = "/set_preview", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> setPreview(@Valid @RequestBody SetVariableRequest request) {
        return DataResult.of(variableService.previewSetVariableSql(
                request.getVariableName(), request.getValue(), request.getScope()));
    }

    @PostMapping("/session/close")
    public DataResult<Boolean> closeSession(@Valid @RequestBody VariableSessionRequest request) {
        connectionContextService.close();
        return DataResult.of(Boolean.TRUE);
    }

    @Data
    public static class VariableSessionRequest extends DataSourceBaseRequest implements IDataSourceConsoleRequestInfo {
        @NotNull
        private Long consoleId;
    }

    @Data
    public static class VariableListRequest extends VariableSessionRequest {
        @NotBlank
        private String scope;

        @NotBlank
        private String kind;
    }

    @Data
    public static class VariableNameRequest extends VariableSessionRequest {
        @NotBlank
        private String name;
    }

    @Data
    public static class SetVariableRequest extends VariableSessionRequest {
        @NotBlank
        private String variableName;

        @NotBlank
        private String value;

        @NotBlank
        private String scope;
    }
}

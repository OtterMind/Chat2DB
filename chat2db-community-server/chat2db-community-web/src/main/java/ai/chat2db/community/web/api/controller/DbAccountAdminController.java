package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.domain.api.service.db.IDbAccountAdminService;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.db.AccountCommandRequest;
import ai.chat2db.community.web.api.model.request.db.AccountRequest;
import ai.chat2db.community.web.api.model.response.db.AccountCapabilityResponse;
import ai.chat2db.community.web.api.model.response.db.AccountExecuteResponse;
import ai.chat2db.community.web.api.model.response.db.AccountGrantSummaryResponse;
import ai.chat2db.community.web.api.model.response.db.AccountPreviewResponse;
import ai.chat2db.community.web.api.model.response.db.AccountResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes account administration endpoints for relational database connections.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/account")
@RestController
public class DbAccountAdminController {

    @Autowired
    private IDbAccountAdminService accountAdminService;

    @Autowired
    private DbWebConverter dbWebConverter;

    /**
     * Returns database accounts.
     * <p>
     * Endpoint: {@code GET /api/rdb/account/capability}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing account capability response.
     */
    @GetMapping("/capability")
    public DataResult<AccountCapabilityResponse> capability(@Valid AccountRequest request) {
        return DataResult.of(dbWebConverter.accountCapability2response(accountAdminService.capability()));
    }

    /**
     * Lists database accounts.
     * <p>
     * Endpoint: {@code GET /api/rdb/account/list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing account response.
     */
    @GetMapping("/list")
    public ListResult<AccountResponse> list(@Valid AccountRequest request) {
        List<AccountResponse> accounts = accountAdminService.listAccounts().stream()
                .map(dbWebConverter::account2response)
                .toList();
        return ListResult.of(accounts);
    }

    /**
     * Handles grants for database accounts.
     * <p>
     * Endpoint: {@code GET /api/rdb/account/grants}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing string.
     */
    @GetMapping("/grants")
    public ListResult<String> grants(@Valid AccountRequest request) {
        return ListResult.of(accountAdminService.showGrants(request.getUser(), request.getHost()));
    }

    /**
     * Summarizes account grants with direct and inherited source labels.
     * <p>
     * Endpoint: {@code GET /api/rdb/account/grant-summary}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing account grant summary response.
     */
    @GetMapping("/grant-summary")
    public DataResult<AccountGrantSummaryResponse> grantSummary(@Valid AccountRequest request) {
        return DataResult.of(dbWebConverter.accountGrantSummary2response(
                accountAdminService.grantSummary(request.getUser(), request.getHost())));
    }

    /**
     * Previews database accounts.
     * <p>
     * Endpoint: {@code POST /api/rdb/account/preview}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing account preview response.
     */
    @PostMapping("/preview")
    public DataResult<AccountPreviewResponse> preview(@Valid @RequestBody AccountCommandRequest request) {
        return DataResult.of(dbWebConverter.accountPreview2response(
                accountAdminService.preview(dbWebConverter.request2command(request))));
    }

    /**
     * Executes database accounts.
     * <p>
     * Endpoint: {@code POST /api/rdb/account/execute}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing account execute response.
     */
    @PostMapping("/execute")
    public DataResult<AccountExecuteResponse> execute(@Valid @RequestBody AccountCommandRequest request) {
        return DataResult.of(dbWebConverter.accountExecute2response(
                accountAdminService.execute(dbWebConverter.request2command(request))));
    }
}

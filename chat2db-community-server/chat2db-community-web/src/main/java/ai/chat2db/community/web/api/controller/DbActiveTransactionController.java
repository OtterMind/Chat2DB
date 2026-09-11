package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction;
import ai.chat2db.community.domain.api.service.db.IDbActiveTransactionService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes active InnoDB transaction inspection (MYSQL-OPS-002).
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/active_transaction")
@RestController
public class DbActiveTransactionController {

    @Autowired
    private IDbActiveTransactionService activeTransactionService;

    /**
     * Lists active InnoDB transactions.
     * <p>
     * Endpoint: {@code GET /api/rdb/active_transaction/list}.
     *
     * @return active transactions.
     */
    @GetMapping("/list")
    public DataResult<List<ActiveTransaction>> list(@Valid DataSourceBaseRequest request) {
        return DataResult.of(activeTransactionService.activeTransactions());
    }
}

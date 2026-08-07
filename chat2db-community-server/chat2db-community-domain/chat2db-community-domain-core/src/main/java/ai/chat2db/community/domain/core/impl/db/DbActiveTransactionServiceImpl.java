package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbActiveTransactionService;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DbActiveTransactionServiceImpl implements IDbActiveTransactionService {

    /**
     * InnoDB transaction metadata joined with the owning processlist row. Works on MySQL
     * 5.7 and 8.0; {@code trx_query} is null without the PROCESS privilege (or for
     * read-only transactions on 8.0, where the query text is intentionally not captured).
     */
    private static final String SQL_ACTIVE_TRANSACTIONS =
            "SELECT t.trx_id, t.trx_state, t.trx_started, "
                    + "TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) AS trx_age_seconds, "
                    + "t.trx_isolation_level, t.trx_rows_locked, t.trx_rows_modified, "
                    + "t.trx_lock_structs, t.trx_mysql_thread_id, "
                    + "p.USER, p.HOST, p.DB, t.trx_query "
                    + "FROM information_schema.innodb_trx t "
                    + "LEFT JOIN information_schema.processlist p ON t.trx_mysql_thread_id = p.ID "
                    + "ORDER BY t.trx_started";

    @Override
    public List<Map<String, Object>> activeTransactions() {
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, SQL_ACTIVE_TRANSACTIONS, resultSet -> {
            List<Map<String, Object>> transactions = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trxId", resultSet.getString("trx_id"));
                row.put("state", resultSet.getString("trx_state"));
                row.put("startedAt", resultSet.getTimestamp("trx_started"));
                row.put("ageSeconds", resultSet.getLong("trx_age_seconds"));
                row.put("isolationLevel", resultSet.getString("trx_isolation_level"));
                row.put("rowsLocked", resultSet.getLong("trx_rows_locked"));
                row.put("rowsModified", resultSet.getLong("trx_rows_modified"));
                row.put("lockStructs", resultSet.getLong("trx_lock_structs"));
                row.put("threadId", resultSet.getLong("trx_mysql_thread_id"));
                row.put("user", resultSet.getString("USER"));
                row.put("host", resultSet.getString("HOST"));
                row.put("db", resultSet.getString("DB"));
                row.put("query", resultSet.getString("trx_query"));
                transactions.add(row);
            }
            return transactions;
        });
    }
}

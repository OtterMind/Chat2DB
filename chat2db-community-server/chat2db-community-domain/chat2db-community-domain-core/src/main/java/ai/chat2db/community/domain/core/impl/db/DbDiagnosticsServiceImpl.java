package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbDiagnosticsService;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;

@Service
public class DbDiagnosticsServiceImpl implements IDbDiagnosticsService {

    @Override
    public String innodbStatus() {
        Connection connection = Chat2DBContext.getConnection();
        String sql = "SHOW ENGINE INNODB STATUS";
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString("Status");
            }
            return null;
        });
    }
}

package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbDiagnosticsService;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;

@Service
public class DbDiagnosticsServiceImpl implements IDbDiagnosticsService {

    private static final String SQL_SHOW_INNODB_STATUS = "SHOW ENGINE INNODB STATUS";
    private static final String FIELD_STATUS = "Status";

    @Override
    public String innodbStatus() {
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, SQL_SHOW_INNODB_STATUS, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString(FIELD_STATUS);
            }
            return null;
        });
    }
}

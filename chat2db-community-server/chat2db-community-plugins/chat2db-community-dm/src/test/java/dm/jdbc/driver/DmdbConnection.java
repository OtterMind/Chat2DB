package dm.jdbc.driver;

import java.sql.Connection;
import java.sql.SQLException;

public interface DmdbConnection extends Connection {

    String getExplainInfo(String sql) throws SQLException;
}

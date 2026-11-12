package ai.chat2db.plugin.postgresql;

import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import ai.chat2db.spi.DefaultSQLExecutor;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Executes PostgreSQL-protocol commands.
 *
 * <p>PostgreSQL does not cast a {@code varchar} parameter implicitly, so text
 * bound with {@code setString} fails against {@code WHERE created_on = ?} on a
 * date column. {@code STRING} and {@code NULL} parameters are bound untyped
 * instead, like a quoted literal or a bare {@code NULL} in the SQL text, and the
 * server infers the type from the placeholder context. Numbers and booleans keep
 * their typed bind calls.
 */
public class PostgreSQLCommandExecutor extends DefaultSQLExecutor {

    public static final PostgreSQLCommandExecutor INSTANCE = new PostgreSQLCommandExecutor();

    protected PostgreSQLCommandExecutor() {
    }

    @Override
    protected void bindParameterValue(PreparedStatement stmt, int parameterIndex, SqlParameterValue value)
            throws SQLException {
        switch (value.getType()) {
            case STRING -> stmt.setObject(parameterIndex, value.getValue(), Types.OTHER);
            case NULL -> stmt.setNull(parameterIndex, Types.OTHER);
            default -> super.bindParameterValue(stmt, parameterIndex, value);
        }
    }
}

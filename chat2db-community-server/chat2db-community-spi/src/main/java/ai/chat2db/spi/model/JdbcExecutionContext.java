package ai.chat2db.spi.model;

import ai.chat2db.community.domain.api.model.result.ExecutionContext;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class JdbcExecutionContext {

    private JdbcExecutionContext() {
    }

    public static ExecutionContext capture(Connection connection) {
        return ExecutionContext.builder()
                .databaseName(catalog(connection))
                .schemaName(schema(connection))
                .build();
    }

    public static Cursor cursor(Connection connection) {
        return cursor(connection, null);
    }

    public static Cursor cursor(Connection connection, String databaseFallback) {
        return new Cursor(capture(connection), databaseFallback);
    }

    public static final class Cursor {

        private ExecutionContext current;
        private ExecutionContext lastObserved;

        private Cursor(ExecutionContext observed, String databaseFallback) {
            this.lastObserved = copy(observed);
            this.current = copy(observed);
            if (isNotBlank(databaseFallback)) {
                this.current.setDatabaseName(databaseFallback);
            }
        }

        public ExecutionContext current() {
            return copy(current);
        }

        public void advance(Connection connection, String databaseFallback) {
            ExecutionContext observed = capture(connection);
            String databaseName = current.getDatabaseName();
            String schemaName = current.getSchemaName();
            if (isNotBlank(observed.getDatabaseName())
                    && (!Objects.equals(observed.getDatabaseName(), lastObserved.getDatabaseName())
                    || !isNotBlank(databaseName))) {
                databaseName = observed.getDatabaseName();
            }
            if (isNotBlank(observed.getSchemaName())
                    && (!Objects.equals(observed.getSchemaName(), lastObserved.getSchemaName())
                    || !isNotBlank(schemaName))) {
                schemaName = observed.getSchemaName();
            }
            if (isNotBlank(databaseFallback)
                    && (!isNotBlank(observed.getDatabaseName())
                    || Objects.equals(observed.getDatabaseName(), lastObserved.getDatabaseName()))) {
                databaseName = databaseFallback;
            }
            current = ExecutionContext.builder()
                    .databaseName(databaseName)
                    .schemaName(schemaName)
                    .build();
            lastObserved = copy(observed);
        }
    }

    private static ExecutionContext copy(ExecutionContext context) {
        if (context == null) {
            return new ExecutionContext();
        }
        return ExecutionContext.builder()
                .databaseName(context.getDatabaseName())
                .schemaName(context.getSchemaName())
                .build();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String catalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException | RuntimeException exception) {
            return null;
        }
    }

    private static String schema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | RuntimeException | AbstractMethodError exception) {
            return null;
        }
    }
}

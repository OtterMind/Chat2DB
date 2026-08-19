package ai.chat2db.plugin.mariadb;

import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MariaDBManagerTest {

    @Test
    void exportTableDataUsesPositiveFetchSize() {
        TestMariaDBManager manage = new TestMariaDBManager();

        manage.exportTableData(null, "test_db", null, "test_table", null);

        assertEquals(1000, manage.batchSize);
    }

    private static class TestMariaDBManager extends MariaDBManager {
        private int batchSize;

        @Override
        protected void exportTableData(Connection connection, String databaseName, String schemaName, String tableName,
                                       TaskExecutionContext context, int batchSize) {
            this.batchSize = batchSize;
        }
    }
}

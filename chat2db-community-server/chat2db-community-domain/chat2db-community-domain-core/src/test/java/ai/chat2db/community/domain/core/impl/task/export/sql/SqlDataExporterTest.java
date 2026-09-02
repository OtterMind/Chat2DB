package ai.chat2db.community.domain.core.impl.task.export.sql;

import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The SQL exporter is now a thin wiring of {@code SqlSink} into the shared pipeline; statement
 * batching itself is covered by {@code SqlSinkTest}.
 */
class SqlDataExporterTest {

    @Test
    void registersAsTheSqlStrategyWithSqlFileConventions() {
        SqlDataExporter exporter = new SqlDataExporter(new ExportCellProcessorChain(List.of()),
                new SqlExecutionPolicyManager(List.of()));

        assertEquals("sql", exporter.type());
    }
}

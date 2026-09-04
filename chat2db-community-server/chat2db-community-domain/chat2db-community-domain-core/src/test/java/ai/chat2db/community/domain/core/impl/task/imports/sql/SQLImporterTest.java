package ai.chat2db.community.domain.core.impl.task.imports.sql;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.ISqlFileImportManager;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLImporterTest {

    @Test
    void sqlFileOptionsRequirePluginSupport() {
        ImportTaskSpec batchSpec = ImportTaskSpec.builder()
                .commitMode("BATCH")
                .errorPolicy("STOP")
                .batchSize(100)
                .build();

        assertTrue(SQLImporter.shouldUseSqlFileOptions(manager(true), batchSpec));
        assertFalse(SQLImporter.shouldUseSqlFileOptions(manager(false), batchSpec));
        assertFalse(SQLImporter.shouldUseSqlFileOptions(null, batchSpec));
    }

    @Test
    void scriptStopKeepsLegacyPathEvenForMySql() {
        ImportTaskSpec legacySpec = ImportTaskSpec.builder()
                .commitMode("SCRIPT")
                .errorPolicy("STOP")
                .build();

        assertFalse(SQLImporter.shouldUseSqlFileOptions(manager(false), legacySpec));
    }

    private ISqlFileImportManager manager(boolean supported) {
        return new ISqlFileImportManager() {
            @Override
            public boolean supportsOptions(ImportTaskSpec spec) {
                return supported;
            }

            @Override
            public ISqlBatchHandler preflightHandler(ImportTaskSpec spec, TaskExecutionContext context,
                                                      Connection connection) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ISqlBatchHandler executionHandler(ImportTaskSpec spec, TaskExecutionContext context,
                                                      int totalStatements) {
                throw new UnsupportedOperationException();
            }
        };
    }
}

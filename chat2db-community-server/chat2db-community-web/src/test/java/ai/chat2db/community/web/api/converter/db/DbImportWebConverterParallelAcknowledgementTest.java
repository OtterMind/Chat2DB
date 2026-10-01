package ai.chat2db.community.web.api.converter.db;

import ai.chat2db.community.domain.api.model.db.MappedImportExecution;
import ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy;
import ai.chat2db.community.web.api.model.request.db.ImportExecuteRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The parallel admission gate rejects ULTRA_FAST imports with rule R1 unless the operator
 * confirmed that the target has no strong relationship or ordering dependency. The frontend
 * sends that acknowledgement on every parallel import, so it has to survive the request ->
 * domain conversion. Losing it silently turned every API-submitted parallel import into a
 * rejected task, which is why this is pinned here.
 */
class DbImportWebConverterParallelAcknowledgementTest {

    private final DbImportWebConverter converter = new DbImportWebConverter();

    @Test
    void parallelAcknowledgementSurvivesTheConversion() {
        ImportExecuteRequest request = new ImportExecuteRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("scale_demo");
        request.setTableName("events_parallel");
        request.setFileId("03d03173-6677-4589-80fa-31d1d269ac7e");
        request.setUnmappedTarget(UnmappedTargetStrategy.DEFAULT);
        request.setMode("ULTRA_FAST");
        request.setConfirmedNoStrongRelations(Boolean.TRUE);

        MappedImportExecution execution = converter.toMappedImportExecution(request);

        assertEquals(Boolean.TRUE, execution.getConfirmedNoStrongRelations(),
                "R1 acknowledgement must reach the domain model, otherwise R1 always blocks");
        assertEquals("ULTRA_FAST", execution.getMode());
    }

    @Test
    void anAbsentAcknowledgementStaysAbsentInsteadOfBeingCoercedToTrue() {
        ImportExecuteRequest request = new ImportExecuteRequest();
        request.setMode("ULTRA_FAST");

        MappedImportExecution execution = converter.toMappedImportExecution(request);

        assertNull(execution.getConfirmedNoStrongRelations(),
                "the gate must not see an acknowledgement the operator never gave");
    }
}

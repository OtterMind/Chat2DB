package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DbPartitionControllerRequestContractTest {

    @Test
    void partitionEndpointRequestsCarryDatasourceBindingContext() {
        assertPartitionRequestContext(new DbPartitionController.PartitionListRequest());
        assertPartitionRequestContext(new DbPartitionController.PartitionRequest());
        assertPartitionRequestContext(new DbPartitionController.AddRequest());
        assertPartitionRequestContext(new DbPartitionController.ReorganizeRequest());
        assertPartitionRequestContext(new DbPartitionController.CoalesceRequest());
        assertPartitionRequestContext(new DbPartitionController.MaintainRequest());
    }

    private static void assertPartitionRequestContext(DataSourceBaseRequest request) {
        request.setDataSourceId(42L);
        request.setDatabaseName("orders_db");
        request.setSchemaName("tenant_schema");

        assertInstanceOf(DataSourceBaseRequest.class, request);
        assertEquals(42L, request.getDataSourceId());
        assertEquals("orders_db", request.getDatabaseName());
        assertEquals("tenant_schema", request.getSchemaName());
    }
}

package ai.chat2db.community.web.api.converter.operation.log;

import ai.chat2db.community.domain.api.model.operation.OperationLog;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationLogPageQueryRequest;
import ai.chat2db.community.web.api.model.request.operation.log.OperationLogQueryRequest;
import ai.chat2db.community.web.api.model.response.operation.log.OperationLogResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationLogConverterTest {

    private final OperationLogConverter converter = new OperationLogConverterImpl();

    @Test
    void request2paramCarriesOperationTypeAndScopeFilters() {
        OperationLogQueryRequest request = new OperationLogQueryRequest();
        request.setPageNo(1);
        request.setPageSize(20);
        request.setSearchKey("from orders");
        request.setDataSourceId(7L);
        request.setDatabaseName("warehouse");
        request.setSchemaName("analytics");
        request.setOperationType("SQL_EXECUTE");

        OpsOperationLogPageQueryRequest param = converter.request2param(request);

        assertEquals("SQL_EXECUTE", param.getOperationType());
        assertEquals("from orders", param.getSearchKey());
        assertEquals(7L, param.getDataSourceId());
        assertEquals("warehouse", param.getDatabaseName());
        assertEquals("analytics", param.getSchemaName());
    }

    @Test
    void toResponseExposesOperationType() {
        OperationLog operationLog = new OperationLog();
        operationLog.setDdl("select 1");
        operationLog.setOperationType("SQL_EXECUTE");

        OperationLogResponse response = converter.toResponse(operationLog);

        assertEquals("SQL_EXECUTE", response.getOperationType());
    }
}

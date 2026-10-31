package ai.chat2db.community.web.api.converter;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.web.api.converter.task.TaskWebConverter;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The import request passes the duplicate and failure policies through to the task spec. An absent
 * value keeps the documented default, an unknown value is rejected - the request must never change
 * the import semantics by accident.
 */
class TaskImportPolicyConverterTest {

    private final TaskWebConverter converter = new TaskWebConverter();

    @Test
    void absentPoliciesKeepTheDocumentedDefaults() {
        ImportTaskSpec spec = converter.importRequest2spec(request(null, null));

        assertEquals("INSERT", spec.getDuplicatePolicy());
        assertEquals("ABORT", spec.getFailurePolicy());
    }

    @Test
    void explicitPoliciesReachTheSpec() {
        ImportTaskSpec spec = converter.importRequest2spec(request("IGNORE", "SKIP_ERROR_ROWS"));

        assertEquals("IGNORE", spec.getDuplicatePolicy());
        assertEquals("SKIP_ERROR_ROWS", spec.getFailurePolicy());
        // The wire value is normalized, so the desktop bridge may send either spelling.
        ImportTaskSpec lowerCase = converter.importRequest2spec(request("ignore", "skip_error_rows"));
        assertEquals("IGNORE", lowerCase.getDuplicatePolicy());
        assertEquals("SKIP_ERROR_ROWS", lowerCase.getFailurePolicy());
    }

    @Test
    void unknownPoliciesAreRejectedWithTheirOwnCode() {
        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> converter.importRequest2spec(request("UPSERT", null)));
        assertEquals("task.import.duplicatePolicy.invalid", duplicate.getCode());

        BusinessException failure = assertThrows(BusinessException.class,
                () -> converter.importRequest2spec(request(null, "CONTINUE")));
        assertEquals("task.import.failurePolicy.invalid", failure.getCode());
    }

    private TaskImportRequest request(String duplicatePolicy, String failurePolicy) {
        TaskImportRequest request = new TaskImportRequest();
        request.setDataSourceId(1L);
        request.setTableName("TARGET_ROWS");
        request.setFormat("CSV");
        request.setDuplicatePolicy(duplicatePolicy);
        request.setFailurePolicy(failurePolicy);
        return request;
    }
}

package ai.chat2db.community.web.api.adapter.file;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskImportUploadSizeFilterTest {

    @Test
    void matchesCanonicalEndpointAcrossMatrixAndEncodedSegments() {
        assertTrue(TaskImportUploadSizeFilter.isImportUploadRequest(
                request("POST", "/api/tasks/import/upload", "")));
        assertTrue(TaskImportUploadSizeFilter.isImportUploadRequest(
                request("POST", "/chat2db/api/tasks;x=1/import/upload;v=1", "/chat2db")));
        assertTrue(TaskImportUploadSizeFilter.isImportUploadRequest(
                request("POST", "/api/tasks/import/%75pload", "")));
        assertFalse(TaskImportUploadSizeFilter.isImportUploadRequest(
                request("POST", "/api/tasks/import", "")));
        assertFalse(TaskImportUploadSizeFilter.isImportUploadRequest(
                request("GET", "/api/tasks/import/upload", "")));
    }

    @Test
    void requiresAKnownContentLengthWithinThePerEndpointLimit() {
        assertTrue(TaskImportUploadSizeFilter.isAllowedContentLength(1024L, 1024L));
        assertFalse(TaskImportUploadSizeFilter.isAllowedContentLength(-1L, 1024L));
        assertFalse(TaskImportUploadSizeFilter.isAllowedContentLength(1025L, 1024L));
    }

    @Test
    void configuredLimitCannotOverflowTheRequestBound() {
        assertTrue(TaskImportUploadSizeFilter.isAllowedContentLength(
                Long.MAX_VALUE, TaskImportUploadSizeFilter.requestLimit(Long.MAX_VALUE)));
    }

    @Test
    void rejectsOversizeRequestBeforeTheMultipartControllerRuns() throws Exception {
        TaskImportUploadSizeFilter filter = new TaskImportUploadSizeFilter(4L);
        MockHttpServletRequest request = requestWithLength(
                TaskImportUploadSizeFilter.MULTIPART_OVERHEAD_BYTES + 5L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    private MockHttpServletRequest requestWithLength(long contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return contentLength;
            }
        };
        request.setMethod("POST");
        request.setRequestURI("/api/tasks/import/upload");
        return request;
    }

    private MockHttpServletRequest request(String method, String uri, String contextPath) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(uri);
        request.setContextPath(contextPath);
        return request;
    }
}

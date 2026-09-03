package ai.chat2db.community.web.api.adapter.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDriverUploadSizeFilterTest {

    @Test
    void matchesOnlyTheDriverUploadEndpoint() {
        assertTrue(JdbcDriverUploadSizeFilter.isDriverUploadRequest(
                "POST", "/api/jdbc/driver/upload", ""));
        assertTrue(JdbcDriverUploadSizeFilter.isDriverUploadRequest(
                "POST", "/chat2db/api/jdbc/driver/upload", "/chat2db"));
        assertTrue(JdbcDriverUploadSizeFilter.isDriverUploadRequest(
                "POST", "/api/jdbc/driver/upload;x=y", ""));
        assertTrue(JdbcDriverUploadSizeFilter.isDriverUploadRequest(
                "POST", "/chat2db/api;v=1/jdbc/driver/upload;x=y", "/chat2db"));
        assertFalse(JdbcDriverUploadSizeFilter.isDriverUploadRequest(
                "POST", "/api/converter/upload", ""));
        assertFalse(JdbcDriverUploadSizeFilter.isDriverUploadRequest(
                "POST", "/api/jdbc/driver/upload;x=y/extra", ""));
        assertFalse(JdbcDriverUploadSizeFilter.isDriverUploadRequest(
                "OPTIONS", "/api/jdbc/driver/upload", ""));
    }

    @Test
    void requiresADeclaredBoundedContentLength() {
        assertTrue(JdbcDriverUploadSizeFilter.isAllowedContentLength(
                JdbcDriverUploadSizeFilter.MAX_DRIVER_UPLOAD_REQUEST_BYTES));
        assertFalse(JdbcDriverUploadSizeFilter.isAllowedContentLength(-1));
        assertFalse(JdbcDriverUploadSizeFilter.isAllowedContentLength(
                JdbcDriverUploadSizeFilter.MAX_DRIVER_UPLOAD_REQUEST_BYTES + 1));
    }
}

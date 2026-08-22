package ai.chat2db.community.domain.core.impl.db;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbDiagnosticsServiceImplTest {

    @Test
    void supportsMysql57AndMysql80() {
        assertFalse(DbDiagnosticsServiceImpl.supportsInnodbStatus("5.6.51"));
        assertTrue(DbDiagnosticsServiceImpl.supportsInnodbStatus("5.7.44"));
        assertTrue(DbDiagnosticsServiceImpl.supportsInnodbStatus("8.0.36"));
    }

    @Test
    void recognizesProcessPrivilegeFailuresThroughWrappedCauses() {
        SQLException denied = new SQLException("Access denied; you need (at least one of) the PROCESS privilege", "42000", 1227);
        assertTrue(DbDiagnosticsServiceImpl.hasProcessPrivilegeError(new IllegalStateException("execution failed", denied)));
        assertFalse(DbDiagnosticsServiceImpl.hasProcessPrivilegeError(new SQLException("table not found", "42S02", 1146)));
    }
}

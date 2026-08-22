package ai.chat2db.community.domain.core.impl.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbSessionServiceImplTest {

    @Test
    void supportsMysql57AndMysql80() {
        assertFalse(DbSessionServiceImpl.supportsSessionInspection("5.6.51"));
        assertTrue(DbSessionServiceImpl.supportsSessionInspection("5.7.44"));
        assertTrue(DbSessionServiceImpl.supportsSessionInspection("8.0.36"));
    }

    @Test
    void onlyAllowsSessionsOwnedByCurrentDatabaseUser() {
        assertTrue(DbSessionServiceImpl.sameMysqlUser("chat2db@localhost", "chat2db"));
        assertTrue(DbSessionServiceImpl.sameMysqlUser("CHAT2DB@10.0.0.1", "chat2db@%"));
        assertFalse(DbSessionServiceImpl.sameMysqlUser("admin@localhost", "chat2db"));
        assertFalse(DbSessionServiceImpl.sameMysqlUser(null, "chat2db"));
    }
}

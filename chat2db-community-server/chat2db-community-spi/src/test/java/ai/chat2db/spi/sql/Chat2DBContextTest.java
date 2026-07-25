package ai.chat2db.spi.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Chat2DBContextTest {

    @Test
    void shouldDescribeUnsupportedDatabaseType() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> Chat2DBContext.getDBConfig("NOT_REGISTERED_FOR_TEST"));

        assertTrue(exception.getMessage().contains("Unsupported database type: NOT_REGISTERED_FOR_TEST"));
    }

    @Test
    void shouldRejectBlankDatabaseType() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> Chat2DBContext.getDBConfig(" "));

        assertTrue(exception.getMessage().contains("Database type must not be blank"));
    }
}

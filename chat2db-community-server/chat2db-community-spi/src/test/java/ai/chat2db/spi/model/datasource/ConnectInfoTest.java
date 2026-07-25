package ai.chat2db.spi.model.datasource;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectInfoTest {

    @Test
    void equalInstancesShouldHaveEqualHashCodes() {
        LocalDateTime modifiedAt = LocalDateTime.of(2026, 7, 25, 12, 0);
        ConnectInfo first = new ConnectInfo();
        first.setDataSourceId(42L);
        first.setGmtModified(modifiedAt);
        first.setConsoleId(1L);
        first.setDatabaseName("first_database");
        ConnectInfo second = new ConnectInfo();
        second.setDataSourceId(42L);
        second.setGmtModified(modifiedAt);
        second.setConsoleId(2L);
        second.setDatabaseName("second_database");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}

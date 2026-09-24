package ai.chat2db.community.sqlx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SqlxExecutableTest {

    @Test
    void readsTheVersionThatFollowsTheProgramName() {
        // `sqlx --version` prints the program name first; taking the first token reported "sqlx".
        assertEquals(Optional.of("0.1.15"), SqlxExecutable.versionFromOutput("sqlx 0.1.15 (OtterMind/sqlx)\n"));
        assertEquals(Optional.of("0.1.16"), SqlxExecutable.versionFromOutput("sqlx 0.1.16 (OtterMind/sqlx)"));
        assertEquals(Optional.of("0.2.0-rc1"),
                SqlxExecutable.versionFromOutput("sqlx 0.2.0-rc1 (OtterMind/sqlx)"));
        assertEquals(Optional.of("0.1.15"), SqlxExecutable.versionFromOutput("0.1.15 (OtterMind/sqlx)"));
    }

    @Test
    void rejectsOutputWithoutAVersionOrWithoutTheMarker() {
        assertEquals(Optional.empty(), SqlxExecutable.versionFromOutput("sqlx (OtterMind/sqlx)"));
        assertEquals(Optional.empty(), SqlxExecutable.versionFromOutput("sqlx 0.1.15"));
        assertEquals(Optional.empty(), SqlxExecutable.versionFromOutput("postgres 16 (PostgreSQL)"));
        assertEquals(Optional.empty(), SqlxExecutable.versionFromOutput(null));
        assertEquals(Optional.empty(), SqlxExecutable.versionFromOutput(""));
    }

    @Test
    void recognizesReleaseVersionStrings() {
        assertTrue(SqlxExecutable.isValidVersion("0.1.15"));
        assertTrue(SqlxExecutable.isValidVersion("1.0.0-rc1"));
        assertFalse(SqlxExecutable.isValidVersion("sqlx"));
        assertFalse(SqlxExecutable.isValidVersion("v0.1.15"));
        assertFalse(SqlxExecutable.isValidVersion(null));
    }
}

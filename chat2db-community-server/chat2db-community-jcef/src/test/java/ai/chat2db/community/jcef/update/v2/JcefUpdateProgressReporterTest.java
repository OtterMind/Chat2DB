package ai.chat2db.community.jcef.update.v2;

import ai.chat2db.community.tools.console.ConsoleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JcefUpdateProgressReporterTest {

    private long now = 1_000_000L;
    private JcefUpdateProgressReporter reporter;
    private ConsoleResult consoleResult;

    @BeforeEach
    void setUp() {
        reporter = new JcefUpdateProgressReporter(() -> now);
        consoleResult = new ConsoleResult();
    }

    @Test
    void pushesTheFirstReportedBlockImmediately() {
        assertTrue(reporter.progress(consoleResult, 0L, 1000L));
    }

    @Test
    void coalescesProgressWithinTheMinimumInterval() {
        assertTrue(reporter.progress(consoleResult, 100L, 1000L));

        now += JcefUpdateProgressReporter.MIN_PUSH_INTERVAL_MILLIS - 1;
        assertFalse(reporter.progress(consoleResult, 200L, 1000L));

        now += 1;
        assertTrue(reporter.progress(consoleResult, 200L, 1000L));
    }

    @Test
    void ignoresProgressThatDoesNotAdvance() {
        assertTrue(reporter.progress(consoleResult, 500L, 1000L));

        now += 10_000L;
        assertFalse(reporter.progress(consoleResult, 500L, 1000L));
        assertFalse(reporter.progress(consoleResult, 400L, 1000L));
    }

    @Test
    void capsIncompleteProgressAtNinetyNinePercent() {
        assertTrue(reporter.progress(consoleResult, 1000L, 1000L));

        now += 10_000L;
        assertFalse(reporter.progress(consoleResult, 999L, 1000L));
    }

    @Test
    void resetLetsTheNextDownloadReportImmediately() {
        assertTrue(reporter.progress(consoleResult, 500L, 1000L));
        assertFalse(reporter.progress(consoleResult, 600L, 1000L));

        reporter.reset();

        assertTrue(reporter.progress(consoleResult, 0L, 1000L));
    }

    @Test
    void reportsProgressWhenTheTotalSizeIsUnknown() {
        assertTrue(reporter.progress(consoleResult, 0L, 0L));

        now += 10_000L;
        assertFalse(reporter.progress(consoleResult, 1024L, 0L));
    }
}

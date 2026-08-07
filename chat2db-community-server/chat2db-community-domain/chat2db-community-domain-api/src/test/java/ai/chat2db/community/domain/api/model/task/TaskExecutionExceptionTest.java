package ai.chat2db.community.domain.api.model.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExecutionExceptionTest {

    @Test
    void publicMessageUsesOnlyExplicitSafeContent() {
        IllegalStateException cause = new IllegalStateException("password=secret\n\tat internal.Stack");

        TaskExecutionException exception = new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                "Could not export query result", "  XLSX row limit reached\nUse CSV instead  ", cause);

        assertEquals("Could not export query result: XLSX row limit reached Use CSV instead",
                exception.publicMessage());
        assertEquals("XLSX row limit reached Use CSV instead", exception.getSafeReason());
        assertFalse(exception.publicMessage().contains("password"));
        assertFalse(exception.publicMessage().contains("internal.Stack"));
    }

    @Test
    void publicMessageFallsBackToSafeMessageWithoutReason() {
        TaskExecutionException exception = new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                "Export failed", new IllegalStateException("private cause"));

        assertEquals("Export failed", exception.publicMessage());
    }

    @Test
    void publicMessageIsLengthLimited() {
        TaskExecutionException exception = new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                "Export failed", "x".repeat(TaskConstants.MAX_PUBLIC_ERROR_MESSAGE_LENGTH), null);

        assertEquals(TaskConstants.MAX_PUBLIC_ERROR_MESSAGE_LENGTH, exception.publicMessage().length());
        assertTrue(exception.publicMessage().endsWith("..."));
    }

    @Test
    void safeReasonUsesThePublicSingleLineLengthLimit() {
        TaskExecutionException exception = new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                "Export failed", "  first line\n" + "x".repeat(TaskConstants.MAX_PUBLIC_ERROR_MESSAGE_LENGTH), null);

        assertEquals(TaskConstants.MAX_PUBLIC_ERROR_MESSAGE_LENGTH, exception.getSafeReason().length());
        assertFalse(exception.getSafeReason().contains("\n"));
        assertTrue(exception.getSafeReason().startsWith("first line "));
        assertTrue(exception.getSafeReason().endsWith("..."));
    }
}

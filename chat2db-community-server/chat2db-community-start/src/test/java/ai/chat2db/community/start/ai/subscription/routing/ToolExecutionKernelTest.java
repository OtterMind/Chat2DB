package ai.chat2db.community.start.ai.subscription.routing;

import ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolExecution;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolExecutionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolStartDecision;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolStartResult;
import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;
import ai.chat2db.community.domain.api.service.storage.IAiSubscriptionStateRepository;
import ai.chat2db.community.start.ai.subscription.routing.tool.ToolExecutionKernel;
import ai.chat2db.community.start.ai.subscription.routing.tool.ToolExecutionKernel.ToolInvocationResult;
import ai.chat2db.community.storage.ai.H2AiSubscriptionStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class ToolExecutionKernelTest {

    @Test
    void beginsJournalBeforeExecutorAndCompletesSafeReference() {
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        AiToolExecution started = new AiToolExecution(
                "a1", 1, "execute_sql", "h", "f", AiToolExecutionState.STARTED, null, Instant.now());
        when(repository.beginToolExecution(eq("a1"), eq(1L), eq("execute_sql"), anyString(), anyString()))
                .thenReturn(new AiToolStartResult(AiToolStartDecision.STARTED, started));
        when(repository.findAttempt("a1")).thenReturn(Optional.of(new AiAttempt(
                "a1", "m1", null, AiAttemptState.ACTIVE, "t", "u", Instant.now(), Instant.now())));
        when(repository.isAttemptLeaseActive("a1")).thenReturn(true);

        AtomicInteger executions = new AtomicInteger();
        ToolExecutionKernel kernel = new ToolExecutionKernel(repository, (tool, args) -> {
            executions.incrementAndGet();
            return "[{\"value\":1}]";
        });

        ToolInvocationResult result = kernel.invoke("a1", 1, "execute_sql", "SELECT 1");
        assertEquals(ToolInvocationResult.Outcome.EXECUTED, result.outcome());
        assertEquals("[{\"value\":1}]", result.responseText());
        assertEquals("sha256:" + ToolExecutionKernel.sha256Hex("[{\"value\":1}]"),
                result.safeResultReference());
        assertEquals(1, executions.get());
        verify(repository).beginToolExecution(eq("a1"), eq(1L), eq("execute_sql"), anyString(), anyString());
        verify(repository).completeToolExecution("a1", 1L, result.safeResultReference());
    }

    @Test
    void completedFingerprintReplaysStoredSafeReferenceWithoutExecutor() {
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        AiToolExecution completed = new AiToolExecution(
                "a1", 1, "execute_sql", "h", "f", AiToolExecutionState.COMPLETED, "safe-ref-1", Instant.now());
        when(repository.beginToolExecution(eq("a1"), eq(3L), eq("execute_sql"), anyString(), anyString()))
                .thenReturn(new AiToolStartResult(AiToolStartDecision.RETURN_RECORDED_RESULT, completed));

        AtomicInteger executions = new AtomicInteger();
        ToolExecutionKernel kernel = new ToolExecutionKernel(repository, (tool, args) -> {
            executions.incrementAndGet();
            return "must-not-run";
        });

        ToolInvocationResult result = kernel.invoke("a1", 3, "execute_sql", "SELECT 1");
        assertEquals(ToolInvocationResult.Outcome.REPLAYED, result.outcome());
        assertEquals("safe-ref-1", result.safeResultReference());
        assertEquals("RESULT_ALREADY_RECORDED:safe-ref-1", result.responseText());
        assertEquals(0, executions.get());
        verify(repository, never()).completeToolExecution(anyString(), anyLong(), anyString());
    }

    @Test
    void startedOrUnknownBlocksWithoutReExecution() {
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        AiToolExecution started = new AiToolExecution(
                "a1", 1, "execute_sql", "h", "f", AiToolExecutionState.STARTED, null, Instant.now());
        when(repository.beginToolExecution(eq("a1"), eq(2L), eq("execute_sql"), anyString(), anyString()))
                .thenReturn(new AiToolStartResult(AiToolStartDecision.BLOCKED_UNCERTAIN, started));
        when(repository.findAttempt("a1")).thenReturn(Optional.of(new AiAttempt(
                "a1", "m1", null, AiAttemptState.TOOL_ACTIVE, "t", "u", Instant.now(), Instant.now())));

        AtomicInteger executions = new AtomicInteger();
        ToolExecutionKernel kernel = new ToolExecutionKernel(repository, (tool, args) -> {
            executions.incrementAndGet();
            return "x";
        });

        ToolInvocationResult result = kernel.invoke("a1", 2, "execute_sql", "SELECT 1");
        assertEquals(ToolInvocationResult.Outcome.BLOCKED_UNCERTAIN, result.outcome());
        assertEquals(0, executions.get());
        assertNull(result.safeResultReference());
    }

    @Test
    void executorExceptionMarksUnknownOutcome() {
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        AiToolExecution started = new AiToolExecution(
                "a1", 1, "execute_sql", "h", "f", AiToolExecutionState.STARTED, null, Instant.now());
        when(repository.beginToolExecution(eq("a1"), eq(1L), eq("execute_sql"), anyString(), anyString()))
                .thenReturn(new AiToolStartResult(AiToolStartDecision.STARTED, started));
        when(repository.findAttempt("a1")).thenReturn(Optional.of(new AiAttempt(
                "a1", "m1", null, AiAttemptState.ACTIVE, "t", "u", Instant.now(), Instant.now())));
        when(repository.markToolOutcomeUnknownAndReleaseLease("a1")).thenReturn(true);

        ToolExecutionKernel kernel = new ToolExecutionKernel(repository, (tool, args) -> {
            throw new RuntimeException("db crash mid-effect");
        });

        ToolInvocationResult result = kernel.invoke("a1", 1, "execute_sql", "DELETE FROM t");
        assertEquals(ToolInvocationResult.Outcome.UNKNOWN, result.outcome());
        assertEquals("TOOL_OUTCOME_UNKNOWN", result.errorCode());
        verify(repository).markToolOutcomeUnknownAndReleaseLease("a1");
        verify(repository, never()).completeToolExecution(anyString(), anyLong(), anyString());
    }

    @Test
    void completionAndUnknownLedgerFailuresStillReturnTerminalUnknown() {
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        AiToolExecution started = new AiToolExecution(
                "a1", 1, "execute_sql", "h", "f", AiToolExecutionState.STARTED, null, Instant.now());
        when(repository.beginToolExecution(eq("a1"), eq(1L), eq("execute_sql"), anyString(), anyString()))
                .thenReturn(new AiToolStartResult(AiToolStartDecision.STARTED, started));
        doThrow(new IllegalStateException("ledger unavailable"))
                .when(repository).completeToolExecution(eq("a1"), eq(1L), anyString());
        when(repository.markToolOutcomeUnknownAndReleaseLease("a1"))
                .thenThrow(new IllegalStateException("ledger still unavailable"));

        ToolExecutionKernel kernel = new ToolExecutionKernel(repository, (tool, args) -> "effect-completed");

        ToolInvocationResult result = kernel.invoke("a1", 1, "execute_sql", "DELETE FROM t");

        assertEquals(ToolInvocationResult.Outcome.UNKNOWN, result.outcome());
        assertEquals("TOOL_OUTCOME_UNKNOWN_LEDGER_UNAVAILABLE", result.errorCode());
        verify(repository).markToolOutcomeUnknownAndReleaseLease("a1");
    }

    @Test
    void completedToolDoesNotReturnResultAfterProviderLeaseWasFenced() {
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        AiToolExecution started = new AiToolExecution(
                "a1", 1, "execute_sql", "h", "f", AiToolExecutionState.STARTED, null, Instant.now());
        when(repository.beginToolExecution(eq("a1"), eq(1L), eq("execute_sql"), anyString(), anyString()))
                .thenReturn(new AiToolStartResult(AiToolStartDecision.STARTED, started));
        when(repository.isAttemptLeaseActive("a1")).thenReturn(false);
        when(repository.markToolOutcomeUnknownAndReleaseLease("a1")).thenReturn(true);

        ToolExecutionKernel kernel = new ToolExecutionKernel(repository, (tool, args) -> "effect-completed");

        ToolInvocationResult result = kernel.invoke("a1", 1, "execute_sql", "UPDATE t SET c = 1");

        assertEquals(ToolInvocationResult.Outcome.UNKNOWN, result.outcome());
        assertEquals("TOOL_OUTCOME_UNKNOWN", result.errorCode());
        verify(repository).completeToolExecution(eq("a1"), eq(1L), anyString());
        verify(repository).markToolOutcomeUnknownAndReleaseLease("a1");
    }

    @Test
    void slowToolFinishingAfterSignOutCannotReturnToProvider() throws Exception {
        H2AiSubscriptionStateRepository repository = new H2AiSubscriptionStateRepository(
                "jdbc:h2:mem:slow_tool_logout;DB_CLOSE_DELAY=-1");
        repository.initialize();
        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.DISCONNECTED, AiProviderConnectionState.CONNECTING, null);
        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.CONNECTING, AiProviderConnectionState.CONNECTED, "m***@example.com");
        repository.createAttempt("a-slow", "m-slow", AiProviderEnum.OPENAI, AiAttemptState.CREATED);
        assertTrue(repository.acquireProviderLease(AiProviderEnum.OPENAI, "a-slow", 0));
        repository.transitionAttempt("a-slow", AiAttemptState.CREATED, AiAttemptState.SUBMITTING, null, null);
        repository.transitionAttempt("a-slow", AiAttemptState.SUBMITTING, AiAttemptState.ACTIVE,
                "thread-slow", "turn-slow");
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ToolExecutionKernel kernel = new ToolExecutionKernel(repository, (tool, args) -> {
            executing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "effect-completed";
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> kernel.invoke(
                    "a-slow", 1, "execute_sql", "UPDATE t SET c = 1"));
            assertTrue(executing.await(5, TimeUnit.SECONDS));

            repository.beginSignOut(AiProviderEnum.OPENAI);
            release.countDown();
            ToolInvocationResult result = future.get(5, TimeUnit.SECONDS);

            assertEquals(ToolInvocationResult.Outcome.UNKNOWN, result.outcome());
            assertEquals(AiAttemptState.TOOL_OUTCOME_UNKNOWN,
                    repository.findAttempt("a-slow").orElseThrow().state());
            assertTrue(repository.currentLease(AiProviderEnum.OPENAI).isEmpty());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void durableAttemptFenceBlocksToolBeforeExecutor() {
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        when(repository.beginToolExecution(eq("a1"), eq(1L), eq("execute_sql"), anyString(), anyString()))
                .thenReturn(new AiToolStartResult(AiToolStartDecision.BLOCKED_UNCERTAIN, null));
        AtomicInteger executions = new AtomicInteger();
        ToolExecutionKernel kernel = new ToolExecutionKernel(repository, (tool, args) -> {
            executions.incrementAndGet();
            return "must-not-run";
        });

        ToolInvocationResult result = kernel.invoke("a1", 1, "execute_sql", "DELETE FROM t");

        assertEquals(ToolInvocationResult.Outcome.BLOCKED_UNCERTAIN, result.outcome());
        assertEquals(0, executions.get());
    }
}

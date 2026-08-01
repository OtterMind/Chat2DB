package ai.chat2db.community.storage.ai;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAccessType;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptOutputKind;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelSnapshot;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderSagaState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiRouteKind;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSecretImportBeginDecision;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSecretImportItemAck;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolExecutionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolStartDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2AiSubscriptionStateRepositoryTest {

    @TempDir
    Path tempDir;

    private H2AiSubscriptionStateRepository repository;

    @BeforeEach
    void setUp() {
        String databaseName = "ai_ledger_" + UUID.randomUUID().toString().replace("-", "");
        repository = new H2AiSubscriptionStateRepository(
                "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
        repository.initialize();
    }

    @Test
    void initializationIsIdempotentAndCreatesVersionedSecretFreeSchema() {
        repository.initialize();

        assertEquals(3, repository.schemaVersion());
        assertEquals(List.of(
                        "AI_ATTEMPT",
                        "AI_ATTEMPT_OUTPUT",
                        "AI_MESSAGE_MODEL_SNAPSHOT",
                        "AI_MODEL_PREFERENCE",
                        "AI_MODEL_SNAPSHOT",
                        "AI_PROVIDER_CONNECTION",
                        "AI_PROVIDER_SAGA",
                        "AI_SECRET_IMPORT",
                        "AI_SECRET_IMPORT_LOCK",
                        "AI_TOOL_EXECUTION"),
                repository.applicationTables());
        assertFalse(repository.schemaContainsColumnMatching("%TOKEN%"));
        assertFalse(repository.schemaContainsColumnMatching("%SECRET%"));
        assertFalse(repository.schemaContainsColumnMatching("%API_KEY%"));
    }

    @Test
    void futureSchemaVersionFailsBeforeApplicationTablesAreMutated() throws Exception {
        String url = "jdbc:h2:mem:future_schema_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE subscription_ai_schema_version "
                    + "(version INT PRIMARY KEY, installed_at TIMESTAMP NOT NULL)");
            statement.execute("INSERT INTO subscription_ai_schema_version(version, installed_at) "
                    + "VALUES(99, CURRENT_TIMESTAMP)");
        }

        H2AiSubscriptionStateRepository future = new H2AiSubscriptionStateRepository(url);
        assertThrows(IllegalStateException.class, future::initialize);

        try (var connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema = 'PUBLIC' "
                             + "AND table_name = 'AI_PROVIDER_CONNECTION'");
             var resultSet = statement.executeQuery()) {
            assertFalse(resultSet.next());
        }
    }

    @Test
    void modelSnapshotAndDefaultMoveTogetherAndIllegalConnectionRegressionFails() {
        Instant discoveredAt = Instant.parse("2026-07-31T02:00:00Z");
        AiModelRef modelRef = new AiModelRef(
                AiAccessType.SUBSCRIPTION,
                AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER,
                "gpt-test");

        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.DISCONNECTED,
                AiProviderConnectionState.CONNECTING,
                null);
        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.CONNECTING,
                AiProviderConnectionState.CONNECTED,
                "m***@example.com");
        repository.replaceModelSnapshotAndGlobalDefault(
                AiProviderEnum.OPENAI,
                discoveredAt,
                List.of(new AiModelSnapshot(modelRef, "GPT Test", discoveredAt, true, null,
                        List.of("low", "high", "xhigh"), "medium")),
                modelRef);

        assertEquals(modelRef, repository.getGlobalDefault().orElseThrow());
        assertEquals(List.of("gpt-test"), repository.listCurrentModels(AiProviderEnum.OPENAI)
                .stream().map(snapshot -> snapshot.modelRef().modelId()).toList());
        AiModelSnapshot stored = repository.listCurrentModels(AiProviderEnum.OPENAI).get(0);
        assertEquals(List.of("low", "high", "xhigh"), stored.supportedReasoningEfforts());
        assertEquals("medium", stored.defaultReasoningEffort());
        assertThrows(IllegalStateException.class, () -> repository.transitionConnection(
                AiProviderEnum.OPENAI,
                AiProviderConnectionState.CONNECTED,
                AiProviderConnectionState.DISCONNECTED,
                null));
    }

    @Test
    void discoveryFailureKeepsConnectionButDisablesLastAvailableSnapshot() {
        Instant discoveredAt = Instant.parse("2026-07-31T02:00:00Z");
        AiModelRef modelRef = subscriptionModel("gpt-test");
        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.DISCONNECTED,
                AiProviderConnectionState.CONNECTING,
                null);
        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.CONNECTING,
                AiProviderConnectionState.CONNECTED,
                "m***@example.com");
        repository.replaceModelSnapshot(AiProviderEnum.OPENAI, discoveredAt,
                List.of(new AiModelSnapshot(modelRef, "GPT Test", discoveredAt, true, null)));

        repository.markDiscoveryFailed(AiProviderEnum.OPENAI, "DISCOVERY_UNAVAILABLE");

        assertEquals(AiProviderConnectionState.DISCOVERY_FAILED,
                repository.connection(AiProviderEnum.OPENAI).state());
        assertEquals("DISCOVERY_UNAVAILABLE",
                repository.connection(AiProviderEnum.OPENAI).discoveryErrorCode());
        AiModelSnapshot stale = repository.listCurrentModels(AiProviderEnum.OPENAI).get(0);
        assertFalse(stale.available());
        assertEquals("DISCOVERY_UNAVAILABLE", stale.disabledReason());
        assertTrue(repository.getGlobalDefault().isEmpty());
    }

    @Test
    void providerRejectedModelIsDisabledAndNoLongerSelected() {
        Instant discoveredAt = Instant.parse("2026-07-31T02:00:00Z");
        AiModelRef modelRef = subscriptionModel("gpt-rejected");
        repository.replaceModelSnapshotAndGlobalDefault(AiProviderEnum.OPENAI, discoveredAt,
                List.of(new AiModelSnapshot(modelRef, "GPT Rejected", discoveredAt, true, null)), modelRef);

        repository.markModelRejected(modelRef, "MODEL_REJECTED");

        AiModelSnapshot rejected = repository.listCurrentModels(AiProviderEnum.OPENAI).get(0);
        assertFalse(rejected.available());
        assertEquals("MODEL_REJECTED", rejected.disabledReason());
        assertTrue(repository.getGlobalDefault().isEmpty());
    }

    @Test
    void busyAtomicReservationCreatesNoSecondAttempt() {
        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.DISCONNECTED, AiProviderConnectionState.CONNECTING, null);
        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.CONNECTING, AiProviderConnectionState.CONNECTED, "m***@example.com");

        assertTrue(repository.tryCreateAttemptAndAcquireProviderLease(
                "attempt-first", "message-first", AiProviderEnum.OPENAI, AiAttemptState.CREATED, 0));
        assertFalse(repository.tryCreateAttemptAndAcquireProviderLease(
                "attempt-busy", "message-busy", AiProviderEnum.OPENAI, AiAttemptState.CREATED, 0));

        assertTrue(repository.findAttempt("attempt-busy").isEmpty());
        assertTrue(repository.listAttemptsByMessageId("message-busy").isEmpty());
        assertTrue(repository.getMessageModelSnapshot("message-busy").isEmpty());
    }

    @Test
    void secretImportLedgerSurvivesRestartAndBlocksAmbiguousRewrite() {
        String jdbcUrl = "jdbc:h2:file:" + tempDir.resolve("secret-import-ledger")
                + ";DB_CLOSE_ON_EXIT=FALSE";
        H2AiSubscriptionStateRepository first = new H2AiSubscriptionStateRepository(jdbcUrl);
        first.initialize();
        Instant expiry = Instant.parse("2026-08-01T00:00:00Z");
        first.startSecretImportAttempt("import-1", expiry);
        assertEquals(AiSecretImportBeginDecision.STARTED,
                first.beginSecretImportItem("import-1", "stable-item", "nonce-hash", expiry, true));
        first.markSecretImportWriteStarted("import-1", "stable-item");
        first.completeSecretImportItem("import-1", "stable-item", new AiSecretImportItemAck(
                "stable-item", "config-1", "Local GPT", "OPENAI", "gpt-test", true, true));
        first.completeSecretImportAttempt("import-1");

        H2AiSubscriptionStateRepository restarted = new H2AiSubscriptionStateRepository(jdbcUrl);
        restarted.initialize();
        assertEquals("config-1", restarted.findSucceededSecretImportItem("stable-item")
                .orElseThrow().configId());
        restarted.startSecretImportAttempt("import-2", expiry);
        assertEquals(AiSecretImportBeginDecision.ALREADY_SUCCEEDED,
                restarted.beginSecretImportItem("import-2", "stable-item", "nonce-2", expiry, false));

        restarted.startSecretImportAttempt("import-3", expiry);
        assertEquals(AiSecretImportBeginDecision.STARTED,
                restarted.beginSecretImportItem("import-3", "uncertain-item", "nonce-3", expiry, false));
        restarted.markSecretImportWriteStarted("import-3", "uncertain-item");

        H2AiSubscriptionStateRepository afterCrash = new H2AiSubscriptionStateRepository(jdbcUrl);
        afterCrash.initialize();
        afterCrash.startSecretImportAttempt("import-4", expiry);
        assertEquals(AiSecretImportBeginDecision.BLOCKED_OUTCOME_UNKNOWN,
                afterCrash.beginSecretImportItem("import-4", "uncertain-item", "nonce-4", expiry, false));
    }

    @Test
    void concurrentSendsAcquireExactlyOneProviderLease() throws Exception {
        repository.createAttempt("attempt-1", "message-1", AiProviderEnum.OPENAI, AiAttemptState.CREATED);
        repository.createAttempt("attempt-2", "message-2", AiProviderEnum.OPENAI, AiAttemptState.CREATED);

        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> calls = List.of(
                    () -> repository.acquireProviderLease(AiProviderEnum.OPENAI, "attempt-1", 0),
                    () -> repository.acquireProviderLease(AiProviderEnum.OPENAI, "attempt-2", 0));
            long acquired = executor.invokeAll(calls).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .count();
            assertEquals(1, acquired);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void terminalAttemptReleasesOnlyItsOwnProviderLease() {
        repository.createAttempt("attempt-1", "message-1", AiProviderEnum.OPENAI, AiAttemptState.CREATED);
        repository.createAttempt("attempt-2", "message-2", AiProviderEnum.OPENAI, AiAttemptState.CREATED);
        assertTrue(repository.acquireProviderLease(AiProviderEnum.OPENAI, "attempt-1", 0));

        assertFalse(repository.releaseProviderLease(AiProviderEnum.OPENAI, "attempt-2"));
        assertEquals("attempt-1", repository.currentLease(AiProviderEnum.OPENAI).orElseThrow().attemptId());
        assertTrue(repository.releaseProviderLease(AiProviderEnum.OPENAI, "attempt-1"));
        assertTrue(repository.currentLease(AiProviderEnum.OPENAI).isEmpty());
        assertTrue(repository.acquireProviderLease(AiProviderEnum.OPENAI, "attempt-2", 0));
    }

    @Test
    void globalDefaultMustReferenceAvailableSnapshotAndAttemptsCanBeListedByMessage() {
        Instant discoveredAt = Instant.parse("2026-07-31T02:00:00Z");
        AiModelRef modelRef = subscriptionModel("gpt-test");
        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.DISCONNECTED, AiProviderConnectionState.CONNECTING, null);
        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.CONNECTING, AiProviderConnectionState.CONNECTED, "m***@example.com");
        repository.replaceModelSnapshot(AiProviderEnum.OPENAI, discoveredAt,
                List.of(new AiModelSnapshot(modelRef, "GPT Test", discoveredAt, true, null)));

        repository.setGlobalDefault(modelRef);
        repository.createAttempt("attempt-a", "message-a", AiProviderEnum.OPENAI, AiAttemptState.CREATED);

        assertEquals(modelRef, repository.getGlobalDefault().orElseThrow());
        assertEquals(List.of("attempt-a"), repository.listAttemptsByMessageId("message-a").stream()
                .map(AiAttempt::attemptId).toList());
        assertThrows(IllegalArgumentException.class, () -> repository.setGlobalDefault(subscriptionModel("missing")));
    }

    @Test
    void signOutFenceClearsLeaseAndPersistsRecoverableSaga() {
        repository.createAttempt("attempt-1", "message-1", AiProviderEnum.OPENAI, AiAttemptState.CREATED);
        assertTrue(repository.acquireProviderLease(AiProviderEnum.OPENAI, "attempt-1", 0));

        String sagaId = repository.beginSignOut(AiProviderEnum.OPENAI);

        assertTrue(repository.currentLease(AiProviderEnum.OPENAI).isEmpty());
        assertEquals(AiAttemptState.INTERRUPTED,
                repository.findAttempt("attempt-1").orElseThrow().state());
        assertEquals(1, repository.connection(AiProviderEnum.OPENAI).fenceGeneration());
        assertEquals(List.of(sagaId), repository.findRecoverableSagas().stream()
                .map(saga -> saga.sagaId()).toList());
        assertEquals(AiProviderSagaState.DISCONNECT_REQUESTED,
                repository.findRecoverableSagas().get(0).state());
    }

    @Test
    void completedSignOutClearsProviderModelsAndLeavesNoRecoverableSaga() {
        Instant discoveredAt = Instant.parse("2026-07-31T02:00:00Z");
        AiModelRef modelRef = subscriptionModel("gpt-test");
        repository.replaceModelSnapshotAndGlobalDefault(
                AiProviderEnum.OPENAI,
                discoveredAt,
                List.of(new AiModelSnapshot(modelRef, "GPT Test", discoveredAt, true, null)),
                modelRef);
        String sagaId = repository.beginSignOut(AiProviderEnum.OPENAI);

        repository.transitionSignOutSaga(sagaId,
                AiProviderSagaState.DISCONNECT_REQUESTED, AiProviderSagaState.WORK_FENCED, null);
        repository.transitionSignOutSaga(sagaId,
                AiProviderSagaState.WORK_FENCED, AiProviderSagaState.LOGOUT_REQUESTED, null);
        repository.transitionSignOutSaga(sagaId,
                AiProviderSagaState.LOGOUT_REQUESTED, AiProviderSagaState.CREDENTIAL_REMOVED, null);
        repository.transitionSignOutSaga(sagaId,
                AiProviderSagaState.CREDENTIAL_REMOVED, AiProviderSagaState.LOCAL_CLEANUP, null);
        repository.transitionSignOutSaga(sagaId,
                AiProviderSagaState.LOCAL_CLEANUP, AiProviderSagaState.DISCONNECTED, null);

        assertTrue(repository.findRecoverableSagas().isEmpty());
        assertTrue(repository.listCurrentModels(AiProviderEnum.OPENAI).isEmpty());
        assertTrue(repository.getGlobalDefault().isEmpty());
        assertEquals(AiProviderConnectionState.DISCONNECTED,
                repository.connection(AiProviderEnum.OPENAI).state());
        assertThrows(IllegalStateException.class, () -> repository.transitionSignOutSaga(
                sagaId, AiProviderSagaState.DISCONNECTED, AiProviderSagaState.LOGOUT_REQUESTED, null));
    }

    @Test
    void failedSignOutProjectsRetryableConnectionState() {
        String sagaId = repository.beginSignOut(AiProviderEnum.OPENAI);
        repository.transitionSignOutSaga(sagaId,
                AiProviderSagaState.DISCONNECT_REQUESTED, AiProviderSagaState.WORK_FENCED, null);
        repository.transitionSignOutSaga(sagaId,
                AiProviderSagaState.WORK_FENCED, AiProviderSagaState.LOGOUT_REQUESTED, null);
        repository.transitionSignOutSaga(sagaId,
                AiProviderSagaState.LOGOUT_REQUESTED, AiProviderSagaState.DISCONNECT_FAILED, "SIGN_OUT_FAILED");

        assertEquals(AiProviderConnectionState.DISCONNECT_FAILED,
                repository.connection(AiProviderEnum.OPENAI).state());

        repository.transitionSignOutSaga(sagaId,
                AiProviderSagaState.DISCONNECT_FAILED, AiProviderSagaState.LOGOUT_REQUESTED, null);
        assertEquals(AiProviderConnectionState.DISCONNECTING,
                repository.connection(AiProviderEnum.OPENAI).state());
    }

    @Test
    void messageModelSnapshotIsImmutableAndAttemptCannotRegress() {
        AiModelRef modelRef = subscriptionModel("gpt-test");
        repository.saveMessageModelSnapshot("message-1", modelRef);
        repository.saveMessageModelSnapshot("message-1", modelRef);

        assertEquals(modelRef, repository.getMessageModelSnapshot("message-1").orElseThrow());
        assertThrows(IllegalStateException.class, () -> repository.saveMessageModelSnapshot(
                "message-1", subscriptionModel("different-model")));

        repository.createAttempt("attempt-1", "message-1", AiProviderEnum.OPENAI, AiAttemptState.CREATED);
        repository.transitionAttempt("attempt-1", AiAttemptState.CREATED, AiAttemptState.SUBMITTING,
                null, null);
        assertThrows(IllegalStateException.class, () -> repository.transitionAttempt(
                "attempt-1", AiAttemptState.SUBMITTING, AiAttemptState.CREATED, null, null));
    }

    @Test
    void completedToolFingerprintReplaysSafeReferenceAndStartedToolBlocksDuplicate() {
        prepareActiveAttempt(repository, "attempt-1", "message-1");

        var first = repository.beginToolExecution(
                "attempt-1", 1, "execute_sql", "args-hash", "effect-hash");
        assertEquals(AiToolStartDecision.STARTED, first.decision());

        var blocked = repository.beginToolExecution(
                "attempt-1", 2, "execute_sql", "args-hash", "effect-hash");
        assertEquals(AiToolStartDecision.BLOCKED_UNCERTAIN, blocked.decision());
        assertEquals(AiToolExecutionState.STARTED, blocked.execution().state());

        repository.completeToolExecution("attempt-1", 1, "result-ref");
        var replay = repository.beginToolExecution(
                "attempt-1", 3, "execute_sql", "args-hash", "effect-hash");
        assertEquals(AiToolStartDecision.RETURN_RECORDED_RESULT, replay.decision());
        assertEquals("result-ref", replay.execution().safeResultReference());
    }

    @Test
    void startupRecoveryMarksStartedToolsAndTheirAttemptsUnknown() {
        prepareActiveAttempt(repository, "attempt-1", "message-1");
        repository.beginToolExecution("attempt-1", 1, "execute_sql", "args-hash", "effect-hash");

        assertEquals(1, repository.recoverStartedToolsAsOutcomeUnknown());
        assertEquals(AiToolExecutionState.OUTCOME_UNKNOWN,
                repository.findToolExecution("attempt-1", 1).orElseThrow().state());
        assertEquals(AiAttemptState.TOOL_OUTCOME_UNKNOWN,
                repository.findAttempt("attempt-1").orElseThrow().state());
    }

    @Test
    void fileBackedRestartRecoversOrphanedAttemptAndReleasesLease() {
        String url = "jdbc:h2:file:" + tempDir.resolve("restart-ledger").toAbsolutePath();
        H2AiSubscriptionStateRepository beforeCrash = new H2AiSubscriptionStateRepository(url);
        beforeCrash.initialize();
        prepareActiveAttempt(beforeCrash, "attempt-crash", "message-crash");

        H2AiSubscriptionStateRepository afterRestart = new H2AiSubscriptionStateRepository(url);
        afterRestart.initialize();
        assertEquals(1, afterRestart.recoverOrphanedAttemptsAndLeases());

        assertEquals(AiAttemptState.OUTCOME_UNKNOWN,
                afterRestart.findAttempt("attempt-crash").orElseThrow().state());
        assertTrue(afterRestart.currentLease(AiProviderEnum.OPENAI).isEmpty());
    }

    @Test
    void restartReconcilesInterruptedAttemptWithUncertainToolToToolOutcomeUnknown() {
        String url = "jdbc:h2:file:" + tempDir.resolve("uncertain-tool-recovery").toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE";
        H2AiSubscriptionStateRepository beforeCrash = new H2AiSubscriptionStateRepository(url);
        beforeCrash.initialize();
        prepareActiveAttempt(beforeCrash, "attempt-uncertain", "message-uncertain");
        beforeCrash.beginToolExecution(
                "attempt-uncertain", 1, "execute_sql", "args-hash", "effect-hash");
        beforeCrash.transitionAttempt("attempt-uncertain", AiAttemptState.ACTIVE,
                AiAttemptState.TOOL_ACTIVE, null, null);
        beforeCrash.transitionAttempt("attempt-uncertain", AiAttemptState.TOOL_ACTIVE,
                AiAttemptState.INTERRUPTED, null, null);

        H2AiSubscriptionStateRepository afterRestart = new H2AiSubscriptionStateRepository(url);
        afterRestart.initialize();
        afterRestart.recoverOrphanedAttemptsAndLeases();

        assertEquals(AiToolExecutionState.OUTCOME_UNKNOWN,
                afterRestart.findToolExecution("attempt-uncertain", 1).orElseThrow().state());
        assertEquals(AiAttemptState.TOOL_OUTCOME_UNKNOWN,
                afterRestart.findAttempt("attempt-uncertain").orElseThrow().state());
        assertTrue(afterRestart.currentLease(AiProviderEnum.OPENAI).isEmpty());
    }

    @Test
    void toolUnknownAndSignOutFenceDurablyRejectFurtherTools() {
        prepareActiveAttempt(repository, "attempt-tool", "message-tool");
        repository.beginToolExecution("attempt-tool", 1, "execute_sql", "args-1", "effect-1");

        assertTrue(repository.markToolOutcomeUnknownAndReleaseLease("attempt-tool"));
        assertEquals(AiAttemptState.TOOL_OUTCOME_UNKNOWN,
                repository.findAttempt("attempt-tool").orElseThrow().state());
        assertTrue(repository.currentLease(AiProviderEnum.OPENAI).isEmpty());
        assertEquals(AiToolStartDecision.BLOCKED_UNCERTAIN,
                repository.beginToolExecution("attempt-tool", 2, "execute_sql", "args-2", "effect-2").decision());

        prepareActiveAttempt(repository, "attempt-signout", "message-signout");
        repository.beginToolExecution(
                "attempt-signout", 1, "execute_sql", "args-signout", "effect-signout");
        repository.transitionAttempt("attempt-signout", AiAttemptState.ACTIVE,
                AiAttemptState.TOOL_ACTIVE, null, null);
        repository.beginSignOut(AiProviderEnum.OPENAI);
        assertEquals(AiAttemptState.TOOL_OUTCOME_UNKNOWN,
                repository.findAttempt("attempt-signout").orElseThrow().state());
        assertEquals(AiToolExecutionState.OUTCOME_UNKNOWN,
                repository.findToolExecution("attempt-signout", 1).orElseThrow().state());
        assertThrows(IllegalStateException.class, () -> repository.completeToolExecution(
                "attempt-signout", 1, "must-not-complete"));
        assertEquals(AiToolStartDecision.BLOCKED_UNCERTAIN,
                repository.beginToolExecution(
                        "attempt-signout", 2, "execute_sql", "args-3", "effect-3").decision());
    }

    @Test
    void terminalFenceRejectsLateAttemptOutput() {
        prepareActiveAttempt(repository, "attempt-fenced-output", "message-fenced-output");
        repository.beginSignOut(AiProviderEnum.OPENAI);

        assertThrows(IllegalStateException.class, () -> repository.appendAttemptOutput(
                "attempt-fenced-output", 1, AiAttemptOutputKind.ASSISTANT_TEXT,
                "late", true, false));
        assertTrue(repository.listAttemptOutputs("attempt-fenced-output").isEmpty());
    }

    @Test
    void conversationPreferenceAndAttemptOutputsAreBackendAuthoritative() {
        AiModelRef modelRef = subscriptionModel("gpt-test");
        repository.setConversationModel("session-1", modelRef);
        assertEquals(modelRef, repository.getConversationModel("session-1").orElseThrow());

        prepareActiveAttempt(repository, "attempt-1", "message-1");
        repository.appendAttemptOutput("attempt-1", 2, AiAttemptOutputKind.ASSISTANT_TEXT,
                "second", true, false);
        repository.appendAttemptOutput("attempt-1", 1, AiAttemptOutputKind.REASONING,
                "first", false, false);

        var outputs = repository.listAttemptOutputs("attempt-1");
        assertEquals(List.of("first", "second"), outputs.stream().map(output -> output.content()).toList());
        assertFalse(outputs.get(1).contextEligible());
        assertThrows(IllegalStateException.class, () -> repository.appendAttemptOutput(
                "attempt-1", 2, AiAttemptOutputKind.ASSISTANT_TEXT, "duplicate", true, false));
    }

    private static AiModelRef subscriptionModel(String modelId) {
        return new AiModelRef(
                AiAccessType.SUBSCRIPTION,
                AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER,
                modelId);
    }

    private static void prepareActiveAttempt(
            H2AiSubscriptionStateRepository target,
            String attemptId,
            String messageId) {
        AiProviderConnectionState state = target.connection(AiProviderEnum.OPENAI).state();
        if (state == AiProviderConnectionState.DISCONNECTED) {
            target.transitionConnection(AiProviderEnum.OPENAI,
                    AiProviderConnectionState.DISCONNECTED, AiProviderConnectionState.CONNECTING, null);
            target.transitionConnection(AiProviderEnum.OPENAI,
                    AiProviderConnectionState.CONNECTING, AiProviderConnectionState.CONNECTED, "m***@example.com");
        }
        target.createAttempt(attemptId, messageId, AiProviderEnum.OPENAI, AiAttemptState.CREATED);
        assertTrue(target.acquireProviderLease(
                AiProviderEnum.OPENAI, attemptId, target.connection(AiProviderEnum.OPENAI).fenceGeneration()));
        target.transitionAttempt(attemptId, AiAttemptState.CREATED, AiAttemptState.SUBMITTING, null, null);
        target.transitionAttempt(attemptId, AiAttemptState.SUBMITTING, AiAttemptState.ACTIVE,
                "thread-" + attemptId, "turn-" + attemptId);
    }
}

package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateOperationCoordinatorTest {

    @Test
    void sharesOneCheckAndMovesThroughTheDownloadInstallationLifecycle() throws Exception {
        UpdateOperationCoordinator coordinator = new UpdateOperationCoordinator();
        UpdateOperationCoordinator.CheckOperation firstCheck = coordinator.beginCheck();
        UpdateOperationCoordinator.CheckOperation secondCheck = coordinator.beginCheck();

        assertTrue(firstCheck.started());
        assertFalse(secondCheck.started());
        assertSame(firstCheck.future(), secondCheck.future());

        Updater.CheckResult available = availableResult();
        coordinator.completeCheck(available);
        assertEquals(UpdateOperationCoordinator.State.AVAILABLE, coordinator.currentState());
        assertSame(available, firstCheck.future().get());

        coordinator.beginDownload();
        assertEquals(UpdateOperationCoordinator.State.DOWNLOADING, coordinator.currentState());
        coordinator.markDownloadReady();
        coordinator.beginInstallation();
        coordinator.completeInstallation();

        assertEquals(UpdateOperationCoordinator.State.IDLE, coordinator.currentState());
        assertFalse(coordinator.currentCheckResult().isNeedsUpdate());
    }

    @Test
    void restoresAvailabilityAfterDownloadOrInstallationFailure() {
        UpdateOperationCoordinator coordinator = new UpdateOperationCoordinator();
        coordinator.beginCheck();
        coordinator.completeCheck(availableResult());

        coordinator.beginDownload();
        assertThrows(BusinessException.class, coordinator::beginDownload);
        coordinator.finishDownloadAfterFailure();
        assertEquals(UpdateOperationCoordinator.State.AVAILABLE, coordinator.currentState());

        coordinator.beginDownload();
        coordinator.markDownloadReady();
        coordinator.beginInstallation();
        coordinator.finishInstallationAfterFailure();
        assertEquals(UpdateOperationCoordinator.State.AVAILABLE, coordinator.currentState());
    }

    @Test
    void retainsReadyStateWhenTheElevatedInstallerCouldNotBeLaunched() {
        UpdateOperationCoordinator coordinator = new UpdateOperationCoordinator();
        coordinator.beginCheck();
        coordinator.completeCheck(availableResult());
        coordinator.beginDownload();
        coordinator.markDownloadReady();
        coordinator.beginInstallation();

        coordinator.restoreReadyToInstallAfterLaunchFailure();

        assertEquals(UpdateOperationCoordinator.State.READY_TO_INSTALL, coordinator.currentState());
    }

    private static Updater.CheckResult availableResult() {
        return new Updater.CheckResult(true, null, Collections.emptyList(), null, false, null,
                new AvailableSnapshot("5.3.2", new byte[]{1}, 0L), null, null, null);
    }
}

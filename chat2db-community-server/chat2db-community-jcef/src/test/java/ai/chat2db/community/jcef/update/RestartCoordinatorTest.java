package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartCoordinatorTest {

    @Test
    void acceptsOnlyOneRestartAfterTheHelperStarts() throws Exception {
        RestartCoordinator coordinator = new RestartCoordinator();
        AtomicInteger starts = new AtomicInteger();

        assertTrue(coordinator.prepare(starts::incrementAndGet));
        assertFalse(coordinator.prepare(starts::incrementAndGet));
        assertEquals(1, starts.get());
    }

    @Test
    void releasesTheGateWhenHelperStartupFails() throws Exception {
        RestartCoordinator coordinator = new RestartCoordinator();

        assertThrows(IOException.class, () -> coordinator.prepare(() -> {
            throw new IOException("cannot launch helper");
        }));
        assertTrue(coordinator.prepare(() -> {
        }));
    }
}

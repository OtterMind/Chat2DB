package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.annotation.NotCliRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterCliRuntimeBoundaryTest {

    @Test
    void updaterIsExcludedFromCliRuntime() {
        assertTrue(Updater.class.isAnnotationPresent(NotCliRuntime.class), Updater.class.getName());
    }
}

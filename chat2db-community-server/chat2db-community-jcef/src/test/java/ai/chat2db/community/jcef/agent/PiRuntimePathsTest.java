package ai.chat2db.community.jcef.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiRuntimePathsTest {

    @Test
    void keepsRuntimeInstallationsOutsideVersionedSessionStorage() {
        PiRuntimePaths paths = new PiRuntimePaths(Path.of("/tmp/chat2db/runtime/agent/pi"));

        assertEquals(Path.of("/tmp/chat2db/runtime/agent/pi"), paths.installations());
        assertEquals(Path.of("/tmp/chat2db/runtime/agent/pi/tmp"), paths.temporary());
    }
}

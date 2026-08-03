package ai.chat2db.community.start.ai.subscription.appserver.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface ProcessLauncher {

    ManagedProcess start(
            List<String> command,
            Path workdir,
            Map<String, String> environment) throws IOException;
}

package ai.chat2db.community.runtime.provider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface ProviderProcessLauncher {

    ManagedProviderProcess start(List<String> command, Path workingDirectory,
                                 Map<String, String> environment) throws IOException;
}

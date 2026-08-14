package ai.chat2db.community.jcef.update;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Applies an already verified update plan using the platform-appropriate mechanism. */
interface InstallationExecutor {

    boolean install(List<FileUpdateAction> actions, Map<String, Path> downloadedFiles, VersionMetadata remoteMetadata);
}

package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.enums.update.UpdateActionType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Produces an update plan without performing any update side effects.
 */
final class UpdatePlanner {

    interface LocalFileInspector {
        Path resolveTarget(String relativePath) throws IOException;

        boolean checksumMatches(Path path, String expectedSha256) throws IOException, NoSuchAlgorithmException;
    }

    private final LocalFileInspector localFiles;

    UpdatePlanner(LocalFileInspector localFiles) {
        this.localFiles = Objects.requireNonNull(localFiles, "localFiles is required");
    }

    List<FileUpdateAction> plan(VersionMetadata local, VersionMetadata remote)
            throws IOException, NoSuchAlgorithmException {
        List<FileUpdateAction> actions = new ArrayList<>();
        Map<String, FileInfo> localFilesMap = local != null && local.files != null
                ? local.getFilesAsMap() : new HashMap<>();
        Map<String, FileInfo> remoteFilesMap = remote.getFilesAsMap();
        boolean versionChanged = local == null || !Objects.equals(local.getVersion(), remote.getVersion());

        for (Map.Entry<String, FileInfo> entry : remoteFilesMap.entrySet()) {
            String fileId = entry.getKey();
            FileInfo remoteFile = entry.getValue();
            FileInfo localFileMeta = localFilesMap.get(fileId);
            if (remoteFile.deleted) {
                if (localFileMeta != null) {
                    if (!Objects.equals(remoteFile.localTargetName, localFileMeta.localTargetName)) {
                        throw new IOException("Deleted update target path does not match local metadata for " + fileId);
                    }
                    actions.add(new FileUpdateAction(UpdateActionType.DELETE_OLD, null, localFileMeta,
                            "Explicitly deleted by remote metadata"));
                }
                continue;
            }

            Path actualLocalPath = localFiles.resolveTarget(remoteFile.localTargetName);
            if (localFileMeta == null) {
                actions.add(new FileUpdateAction(UpdateActionType.DOWNLOAD_NEW, remoteFile, null, "New file"));
            } else if (!Files.exists(actualLocalPath)) {
                actions.add(new FileUpdateAction(UpdateActionType.UPDATE_EXISTING, remoteFile, localFileMeta,
                        "File missing on disk"));
            } else if (!Objects.equals(remoteFile.sha256, localFileMeta.sha256)) {
                actions.add(new FileUpdateAction(UpdateActionType.UPDATE_EXISTING, remoteFile, localFileMeta,
                        "Metadata checksum changed"));
            } else if ("zip".equals(remoteFile.type)) {
                boolean keepLocal = !versionChanged && Files.isDirectory(actualLocalPath);
                actions.add(new FileUpdateAction(keepLocal ? UpdateActionType.KEEP_LOCAL
                        : UpdateActionType.UPDATE_EXISTING, remoteFile, localFileMeta,
                        keepLocal ? "Same-version ZIP directory exists and metadata matches"
                                : versionChanged ? "ZIP payloads are always replaced across versions"
                                : "ZIP directory missing or is not a directory"));
            } else if (localFiles.checksumMatches(actualLocalPath, remoteFile.sha256)) {
                actions.add(new FileUpdateAction(UpdateActionType.KEEP_LOCAL, remoteFile, localFileMeta,
                        "On-disk checksum matches"));
            } else {
                actions.add(new FileUpdateAction(UpdateActionType.UPDATE_EXISTING, remoteFile, localFileMeta,
                        "On-disk file corrupt or changed"));
            }
        }
        return actions;
    }
}

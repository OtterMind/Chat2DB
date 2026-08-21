package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.enums.update.UpdateActionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdatePlannerTest {

    @Test
    void replacesZipDirectoriesAcrossVersionsEvenWhenArchiveMetadataMatches(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve("lib"));
        VersionMetadata local = metadata("5.3.1", zip("lib.zip", "lib", "same-checksum"));
        VersionMetadata remote = metadata("5.3.2", zip("lib.zip", "lib", "same-checksum"));

        List<FileUpdateAction> actions = planner(directory).plan(local, remote);

        assertEquals(UpdateActionType.UPDATE_EXISTING, actions.get(0).actionType);
    }

    @Test
    void keepsAnExistingZipDirectoryForARepeatedSameVersionPlan(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve("dist"));
        VersionMetadata local = metadata("5.3.2", zip("dist.zip", "dist", "same-checksum"));
        VersionMetadata remote = metadata("5.3.2", zip("dist.zip", "dist", "same-checksum"));

        List<FileUpdateAction> actions = planner(directory).plan(local, remote);

        assertEquals(UpdateActionType.KEEP_LOCAL, actions.get(0).actionType);
    }

    private static UpdatePlanner planner(Path directory) {
        return new UpdatePlanner(new UpdatePlanner.LocalFileInspector() {
            @Override
            public Path resolveTarget(String relativePath) {
                return directory.resolve(relativePath);
            }

            @Override
            public boolean checksumMatches(Path path, String expectedSha256) {
                return false;
            }
        });
    }

    private static VersionMetadata metadata(String version, FileInfo file) {
        VersionMetadata metadata = new VersionMetadata();
        metadata.setVersion(version);
        metadata.setFiles(List.of(file));
        return metadata;
    }

    private static FileInfo zip(String id, String target, String checksum) {
        FileInfo file = new FileInfo();
        file.setId(id);
        file.setServerFileName(id);
        file.setLocalTargetName(target);
        file.setSha256(checksum);
        file.setType("zip");
        return file;
    }
}

package ai.chat2db.community.runtime.workspace;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeArtifactManifestReaderTest {

    @TempDir
    Path workspace;

    @Test
    void readsOnlyTheFixedWorkspaceManifestFile() throws Exception {
        Files.writeString(workspace.resolve(RuntimeArtifactManifestReader.FILE_NAME), """
                [{
                  "artifactId": "report-1",
                  "type": "REPORT",
                  "title": "Report",
                  "mimeType": "text/markdown",
                  "size": 8,
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "content": "# Report"
                }]
                """);

        var manifests = new RuntimeArtifactManifestReader().read(workspace);

        assertEquals(1, manifests.size());
        assertEquals("report-1", manifests.get(0).getArtifactId());
        assertEquals(AgentArtifactTypeEnum.REPORT, manifests.get(0).getType());
    }

    @Test
    void rejectsManifestSymlink() throws Exception {
        Path outside = workspace.resolve("outside.json");
        Files.writeString(outside, "[]");
        Files.createSymbolicLink(workspace.resolve(RuntimeArtifactManifestReader.FILE_NAME), outside);

        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeArtifactManifestReader().read(workspace));
    }
}

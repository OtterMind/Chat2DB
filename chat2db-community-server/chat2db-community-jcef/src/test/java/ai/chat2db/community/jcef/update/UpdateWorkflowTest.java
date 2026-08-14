package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.console.ConsoleResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateWorkflowTest {

    @Test
    void checksForAnAvailableUpdateWithoutTheUpdaterFacade(@TempDir Path temporaryDirectory) {
        FakeUpdateSource source = new FakeUpdateSource().manifest("""
                {"version":"5.3.2","forceUpdate":false,"files":[]}
                """);
        RecordingProgress progress = new RecordingProgress();
        UpdateWorkflow workflow = newWorkflow(temporaryDirectory, source, progress);

        Updater.CheckResult result = workflow.check();

        assertTrue(result.isNeedsUpdate());
        assertFalse(result.isCheckFailed());
        assertNotNull(result.getAvailableSnapshot());
        assertEquals(1, source.fetchCount());
    }

    @Test
    void mapsCheckFailureWithoutTheUpdaterFacade(@TempDir Path temporaryDirectory) {
        FakeUpdateSource source = new FakeUpdateSource().manifest("{" + "\"forceUpdate\":false}");
        RecordingProgress progress = new RecordingProgress();
        UpdateWorkflow workflow = newWorkflow(temporaryDirectory, source, progress);

        Updater.CheckResult result = workflow.check();

        assertTrue(result.isCheckFailed());
        assertEquals(UpdateFailureStage.CHECK, result.getFailureStage());
        assertEquals(UpdateFailureReason.INVALID_MANIFEST, result.getFailureReason());
        assertTrue(progress.messages.stream().anyMatch(message -> message.startsWith("ERROR:")));
    }

    private static UpdateWorkflow newWorkflow(Path directory, FakeUpdateSource source, RecordingProgress progress) {
        ObjectMapper mapper = new ObjectMapper();
        UpdateOperationCoordinator coordinator = new UpdateOperationCoordinator();
        UpdatePathPolicy paths = new UpdatePathPolicy(directory, directory.resolve("downloads"));
        LocalVersionStore localVersions = new LocalVersionStore(directory.resolve("local_version.json"), mapper,
                progress::appendLog, message -> { });
        ManifestValidator validator = new ManifestValidator(new ManifestValidator.PathResolver() {
            @Override
            public Path resolveApplicationTarget(String relativePath) throws IOException {
                return paths.resolveApplicationRelativePath(relativePath);
            }

            @Override
            public Path resolveTemporaryPayload(String fileName) throws IOException {
                return paths.resolveTemporaryFile(fileName);
            }
        });
        UpdatePlanner planner = new UpdatePlanner(new UpdatePlanner.LocalFileInspector() {
            @Override
            public Path resolveTarget(String relativePath) throws IOException {
                return paths.resolveApplicationRelativePath(relativePath);
            }

            @Override
            public boolean checksumMatches(Path path, String expectedSha256) {
                return false;
            }
        });
        ResumablePayloadDownloader downloader = new ResumablePayloadDownloader(source,
                new ResumablePayloadDownloader.DownloadPaths() {
                    @Override
                    public Path resolvePayload(String fileName) throws IOException {
                        return paths.resolveTemporaryFile(fileName);
                    }

                    @Override
                    public Path resolvePartial(Path payloadPath) throws IOException {
                        return paths.resolvePartialDownloadFile(payloadPath);
                    }
                }, (path, checksum) -> false, progress::appendLog);
        InstallationExecutor noOpInstaller = (actions, files, metadata) -> true;
        return new UpdateWorkflow(new UpdateRuntimeComponents(coordinator, new UpdateChecker(source, localVersions),
                validator, planner, downloader, localVersions,
                new UpdateBackupStore(progress::appendLog, progress::appendLog), noOpInstaller, noOpInstaller,
                source, mapper, directory, System::nanoTime, () -> progress, exception -> { }, progress::appendLog));
    }

    private static final class RecordingProgress implements UpdateWorkflow.ProgressReporter {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void appendLog(String message) {
            messages.add(message);
        }

        @Override
        public void resetProgressTracker() {
        }

        @Override
        public void setProgress(int value, String message, ConsoleResult consoleResult) {
        }
    }
}

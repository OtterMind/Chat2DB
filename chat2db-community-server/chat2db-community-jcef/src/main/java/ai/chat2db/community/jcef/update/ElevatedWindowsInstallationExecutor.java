package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Creates and launches the Windows elevated-helper plan; the helper remains the security boundary. */
final class ElevatedWindowsInstallationExecutor implements InstallationExecutor {

    private final Path appDirectory;
    private final ObjectMapper objectMapper;
    private final Consumer<String> progressLog;
    private final Consumer<Exception> errorLog;

    ElevatedWindowsInstallationExecutor(Path appDirectory, ObjectMapper objectMapper, Consumer<String> progressLog,
                                        Consumer<Exception> errorLog) {
        this.appDirectory = appDirectory.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.progressLog = progressLog;
        this.errorLog = errorLog;
    }

    @Override
    public boolean install(List<FileUpdateAction> actions, Map<String, Path> downloadedFiles, VersionMetadata remoteMetadata) {
        progressLog.accept("Preparing for update via auxiliary process...");
        try {
            UpdatePlan plan = new UpdatePlan();
            plan.setTasks(actions);
            plan.setRemoteMetadata(remoteMetadata);
            plan.setDownloadedFiles(downloadedFiles.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toAbsolutePath().toString())));
            Path planPath = Files.createTempFile("chat2db-update-plan-", ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(planPath.toFile(), plan);
            progressLog.accept("Update plan created at: " + planPath);

            Path updaterJar = appDirectory.resolve("updater.jar");
            if (!Files.exists(updaterJar)) {
                throw new FileNotFoundException("Updater executable not found at: " + updaterJar);
            }
            String javaExecutable = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toAbsolutePath().toString();
            ProcessBuilder processBuilder = processBuilder(javaExecutable, updaterJar, planPath);
            progressLog.accept("Launching updater process. The application will now close.");
            processBuilder.start();
            return true;
        } catch (Exception exception) {
            errorLog.accept(exception);
            progressLog.accept("FATAL ERROR: Could not start the update process. " + exception.getMessage());
            return false;
        }
    }

    ProcessBuilder processBuilder(String javaExecutable, Path updaterJar, Path planPath) {
        ProcessBuilder processBuilder = new ProcessBuilder("wscript.exe",
                appDirectory.resolve("run-as-admin.vbs").toString(), javaExecutable, updaterJar.toString(),
                planPath.toString(), appDirectory.toString(), "chat2db-community://restart");
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }
}

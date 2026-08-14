package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Manages completed update backup sessions without participating in update planning. */
final class UpdateBackupStore {

    private static final String DIRECTORY_NAME = ".chat2db-update-backups";
    private static final String OWNER_PID_FILE = ".owner-pid";

    private final Consumer<String> progressLog;
    private final Consumer<String> warningLog;

    UpdateBackupStore(Consumer<String> progressLog, Consumer<String> warningLog) {
        this.progressLog = progressLog;
        this.warningLog = warningLog;
    }

    void clearCompletedSessions(Path applicationDirectory) {
        Path backupDirectory = applicationDirectory.toAbsolutePath().normalize().resolve(DIRECTORY_NAME);
        if (Files.isSymbolicLink(backupDirectory) || !Files.isDirectory(backupDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(backupDirectory)) {
                warningLog.accept("Keeping symbolic-link update backup directory: " + backupDirectory);
            }
            return;
        }
        List<Path> sessions;
        try (Stream<Path> stream = Files.list(backupDirectory)) {
            sessions = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        } catch (IOException exception) {
            progressLog.accept("ERROR: Could not list old backups: " + exception.getMessage());
            return;
        }
        for (Path session : sessions) {
            try {
                if (!belongsToPreviousProcess(session)) {
                    continue;
                }
                progressLog.accept("Deleting completed update backup session: " + session.getFileName());
                deleteRecursively(session);
            } catch (IOException exception) {
                progressLog.accept("ERROR: Failed to delete update backup session " + session.getFileName() + ": "
                        + exception.getMessage());
            }
        }
        try (Stream<Path> remaining = Files.list(backupDirectory)) {
            if (remaining.findAny().isEmpty()) {
                Files.deleteIfExists(backupDirectory);
            }
        } catch (IOException exception) {
            progressLog.accept("ERROR: Failed to delete empty update backup directory: " + exception.getMessage());
        }
    }

    private boolean belongsToPreviousProcess(Path session) {
        try {
            Path ownerFile = session.resolve(OWNER_PID_FILE);
            return Files.isRegularFile(ownerFile)
                    && Long.parseLong(Files.readString(ownerFile).trim()) != ProcessHandle.current().pid();
        } catch (IOException | NumberFormatException exception) {
            warningLog.accept("Keeping update backup session with an invalid owner marker: " + session);
            return false;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}

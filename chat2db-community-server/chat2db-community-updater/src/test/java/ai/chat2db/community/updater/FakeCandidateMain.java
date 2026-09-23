package ai.chat2db.community.updater;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class FakeCandidateMain {

    private FakeCandidateMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--hold".equals(args[0])) {
            // Stands in for a running application so the instance check has something to find.
            Thread.sleep(30_000L);
            return;
        }
        Path installRoot = Path.of(args[0]);
        Path storageDirectory = Path.of(args[1]);
        boolean failTrial = Boolean.parseBoolean(args[2]);
        Path updateRoot = Path.of(args[3]);
        boolean failNormal = args.length > 4 && Boolean.parseBoolean(args[4]);
        String version = Files.readString(installRoot.resolve("version.txt"));
        if ("old".equals(version)) {
            Files.writeString(installRoot.resolve("rollback-restarted.txt"), String.join("|",
                System.getenv().getOrDefault("CHAT2DB_UPDATE_FAILURE_FROM_VERSION", ""),
                System.getenv().getOrDefault("CHAT2DB_UPDATE_FAILURE_TO_VERSION", ""),
                System.getenv().getOrDefault("CHAT2DB_UPDATE_FAILURE_LOG", "")
            ));
            return;
        }
        String trialTransactionId = System.getenv("CHAT2DB_UPDATE_TRANSACTION");
        String normalTransactionId = System.getenv("CHAT2DB_UPDATE_NORMAL_TRANSACTION");
        boolean trial = trialTransactionId != null && !trialTransactionId.isBlank();
        String transactionId = trial ? trialTransactionId : normalTransactionId;
        if (transactionId == null || transactionId.isBlank()) {
            return;
        }
        Path healthFile = updateRoot.resolve("update/health-" + transactionId + ".json");
        Path temporaryHealthFile = healthFile.resolveSibling(healthFile.getFileName() + ".tmp");
        Files.createDirectories(healthFile.getParent());
        if (trial) {
            Files.writeString(storageDirectory.resolve("chat2db.db"), "migrated-schema");
        }
        if ((trial && failTrial) || (!trial && failNormal)) {
            System.exit(7);
        }
        if (!trial) {
            Files.writeString(installRoot.resolve("normal.pid"), Long.toString(ProcessHandle.current().pid()));
            Files.writeString(installRoot.resolve("normal-restarted.txt"), "normal");
        }
        String status = trial ? "TRIAL_HEALTHY" : "NORMAL_HEALTHY";
        String targetVersion = System.getenv("CHAT2DB_UPDATE_TARGET_VERSION");
        Files.writeString(temporaryHealthFile, "{\"transactionId\":\"" + transactionId
            + "\",\"version\":\"" + targetVersion + "\",\"status\":\"" + status
            + "\",\"processId\":" + ProcessHandle.current().pid()
            + ",\"timestampEpochMillis\":1}");
        Files.move(temporaryHealthFile, healthFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
        Thread.sleep(2_000L);
    }

}

package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Three-layer resume persistence for bulk import/export tasks.
 *
 * <p><b>Layer 1</b> appends phase/progress records to a per-task NDJSON journal, so the freshest
 * stage survives a hard kill even when the snapshot interval has not elapsed. <b>Layer 2</b>, the
 * task-storage {@code ResumeState} checkpoints, is written by the callers through
 * {@code TaskExecutionContext#checkpoint} — this class only contributes candidates to the recovery
 * resolution. <b>Layer 3</b> writes generational JSON snapshots and maintains a {@code committed}
 * pointer that always names the generation <em>before</em> the last successfully committed one:
 * the guaranteed-consistent fallback of the resume chain, mirroring "restore from before the last
 * successful commit".
 *
 * <p>Every record carries a SHA-256 checksum of its payload; torn records (truncated writes, power
 * loss mid-rename) fail validation and recovery falls back to the next candidate. Snapshots are
 * written atomically (temp file + rename + force), so a reader never observes a half-written file.
 * Recovery resolves the newest <em>valid</em> candidate: because every candidate stores a durable
 * watermark (all rows below it are committed), any valid candidate is equally safe and the newest
 * simply minimises reprocessing.
 */
@Slf4j
public final class TaskResumeJournal {

    /** Generations kept on disk: the newest and the committed fallback behind it. */
    private static final int KEEP_GENERATIONS = 2;

    private static final String PROGRESS_FILE = "progress.ndjson";

    private static final String POINTER_FILE = "committed.txt";

    private final File directory;

    private final Map<String, Object> identity;

    private final Writer progressWriter;

    private TaskResumeJournal(File directory, Map<String, Object> identity, Writer progressWriter) {
        this.directory = directory;
        this.identity = identity;
        this.progressWriter = progressWriter;
    }

    /** Task-scoped journal directory under the application state path. */
    public static File directoryFor(Long taskId) {
        return new File(ConfigUtils.getBasePath(),
                "task-journal" + File.separatorChar + "task-" + (taskId == null ? 0L : taskId));
    }

    /**
     * Opens the journal for writing, or {@code null} when the state path is unusable — journaling
     * is an enhancement and must never block the task itself.
     *
     * @param identity source identity (length/lastModified) stamped onto every record so recovery
     *                 can reject checkpoints of a changed input
     */
    public static TaskResumeJournal open(Long taskId, Map<String, Object> identity) {
        return openDirectory(directoryFor(taskId), identity);
    }

    /** Directory-injected variant for tests and callers that own their persistence path. */
    static TaskResumeJournal openDirectory(File directory, Map<String, Object> identity) {
        try {
            Files.createDirectories(directory.toPath());
            Writer writer = Files.newBufferedWriter(directory.toPath().resolve(PROGRESS_FILE),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new TaskResumeJournal(directory, identity, writer);
        } catch (Throwable openFailure) {
            log.warn("Resume journal unavailable; continuing without stage persistence", openFailure);
            return null;
        }
    }

    /** Appends one progress record (Layer 1); flushes but never throws into the task. */
    public synchronized void progress(String phase, long rowsDone) {
        if (progressWriter == null) {
            return;
        }
        try {
            JSONObject payload = basePayload("progress", rowsDone);
            payload.put("phase", phase);
            progressWriter.write(signed(payload));
            progressWriter.write("\n");
            progressWriter.flush();
        } catch (Throwable journalFailure) {
            log.warn("Resume journal write failed; continuing", journalFailure);
        }
    }

    /**
     * Commits the next full-state generation (Layer 3) and then repoints the committed fallback at
     * the previous generation, so the recovery floor always stays one commit behind; older
     * generations are pruned. Never throws into the task.
     */
    public synchronized void snapshot(long rowsDone) {
        try {
            long seq = nextGeneration();
            JSONObject payload = basePayload("snapshot", rowsDone);
            payload.put("seq", seq);
            writeGeneration(seq, payload);
            if (seq > 1) {
                writePointer(seq - 1);
                pruneGenerations(seq);
            }
        } catch (Throwable snapshotFailure) {
            log.warn("Resume snapshot failed; the previous fallback stays in place", snapshotFailure);
        }
    }

    /** Closes the journal and, on a clean run, removes the whole directory. */
    public synchronized void cleanup() {
        preserve();
        deleteQuietly(directory);
    }

    /** Closes the writer while preserving every recovery candidate after a failed run. */
    public synchronized void preserve() {
        try {
            if (progressWriter != null) {
                progressWriter.close();
            }
        } catch (Throwable closeFailure) {
            log.warn("Resume journal close failed", closeFailure);
        }
    }

    /** Newest intact generation snapshot, or empty when none validates. */
    public static Optional<Snapshot> recoverNewest(File directory) {
        return generations(directory).stream()
                .map(TaskResumeJournal::readGeneration)
                .flatMap(Optional::stream)
                .max(Comparator.comparingLong(Snapshot::seq));
    }

    /** The committed fallback: the generation the pointer names (one behind the last commit). */
    public static Optional<Snapshot> recoverCommitted(File directory) {
        try {
            File pointer = directory.toPath().resolve(POINTER_FILE).toFile();
            if (!pointer.isFile()) {
                return Optional.empty();
            }
            long seq = Long.parseLong(Files.readString(pointer.toPath(), StandardCharsets.UTF_8).trim());
            return readGeneration(directory.toPath().resolve("gen-" + seq + ".json").toFile());
        } catch (Throwable pointerFailure) {
            log.debug("Committed fallback unreadable; skipping this candidate", pointerFailure);
            return Optional.empty();
        }
    }

    /** Watermark of the last valid progress record in the NDJSON tail. */
    public static Optional<Snapshot> recoverTail(File directory) {
        try {
            File progress = directory.toPath().resolve(PROGRESS_FILE).toFile();
            if (!progress.isFile()) {
                return Optional.empty();
            }
            Optional<Snapshot> newestValid = Optional.empty();
            try (BufferedReader reader = Files.newBufferedReader(progress.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        Optional<Snapshot> record = parse(line.trim());
                        if (record.isPresent()) {
                            newestValid = record;
                        }
                    }
                }
            }
            return newestValid;
        } catch (Throwable tailFailure) {
            log.debug("Journal tail unreadable; skipping this candidate", tailFailure);
            return Optional.empty();
        }
    }

    // --- internal persistence plumbing ----------------------------------------------------

    private JSONObject basePayload(String kind, long rowsDone) {
        JSONObject payload = new JSONObject();
        payload.put("kind", kind);
        payload.put("rowsDone", rowsDone);
        payload.put("ts", System.currentTimeMillis());
        if (identity != null) {
            payload.put("identity", identity);
        }
        return payload;
    }

    /** Signs the payload with a SHA-256 checksum of its own JSON and serialises the result. */
    private static String signed(JSONObject payload) {
        String body = payload.toJSONString();
        JSONObject signed = JSONObject.parseObject(body);
        signed.put("checksum", sha256(body));
        return signed.toJSONString();
    }

    /** Parses and checksum-validates one record; empty on any mismatch or parse problem. */
    private static Optional<Snapshot> parse(String line) {
        try {
            JSONObject record = JSONObject.parseObject(line);
            String checksum = record.getString("checksum");
            if (checksum == null) {
                return Optional.empty();
            }
            record.remove("checksum");
            if (!checksum.equals(sha256(record.toJSONString()))) {
                return Optional.empty();
            }
            if (!"progress".equals(record.getString("kind"))
                    && !"snapshot".equals(record.getString("kind"))) {
                return Optional.empty();
            }
            Long rowsDone = record.getLong("rowsDone");
            if (rowsDone == null || rowsDone < 0) {
                return Optional.empty();
            }
            return Optional.of(new Snapshot(rowsDone, record.getLongValue("seq"),
                    record.getLongValue("ts"), record.getJSONObject("identity")));
        } catch (Throwable parseFailure) {
            log.debug("Resume record failed validation", parseFailure);
            return Optional.empty();
        }
    }

    private long nextGeneration() {
        return generations(directory).stream().mapToLong(this::generationSeq).max().orElse(0L) + 1;
    }

    private long generationSeq(File file) {
        String name = file.getName();
        try {
            return Long.parseLong(name.substring("gen-".length(), name.length() - ".json".length()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static List<File> generations(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("gen-") && name.endsWith(".json"));
        return files == null ? List.of() : List.of(files);
    }

    private void writeGeneration(long seq, JSONObject payload) throws IOException {
        Path target = directory.toPath().resolve("gen-" + seq + ".json");
        writeAtomically(target, signed(payload));
    }

    private void writePointer(long seq) throws IOException {
        writeAtomically(directory.toPath().resolve(POINTER_FILE), String.valueOf(seq));
    }

    /** Temp file + fsync + atomic rename: a reader either sees the old file or the complete new one. */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void pruneGenerations(long newestSeq) {
        long floor = newestSeq - (KEEP_GENERATIONS - 1);
        for (File file : generations(directory)) {
            long seq = generationSeq(file);
            if (seq > 0 && seq < floor && !file.delete()) {
                log.debug("Could not prune stale resume generation {}", file.getName());
            }
        }
    }

    private static Optional<Snapshot> readGeneration(File file) {
        try {
            return parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (Throwable readFailure) {
            log.debug("Resume generation {} failed validation", file.getName(), readFailure);
            return Optional.empty();
        }
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception digestFailure) {
            throw new IllegalStateException("SHA-256 unavailable", digestFailure);
        }
    }

    private static void deleteQuietly(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteQuietly(file);
                } else if (!file.delete()) {
                    log.debug("Could not delete resume journal file {}", file.getName());
                }
            }
        }
        if (!directory.delete()) {
            log.debug("Could not delete resume journal directory {}", directory);
        }
    }

    /**
     * One validated resume candidate. {@code rowsDone} means every source row with a position at
     * or below it is durably applied; {@code identity} carries the source fingerprint for staleness
     * checks; {@code seq} is 0 for journal-tail records.
     */
    public record Snapshot(long rowsDone, long seq, long timestamp, Map<String, Object> identity) {
    }
}

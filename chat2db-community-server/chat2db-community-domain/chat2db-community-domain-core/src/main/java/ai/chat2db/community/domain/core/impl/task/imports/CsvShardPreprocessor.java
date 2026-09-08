package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/** Physically slices an admitted CSV at logical-record boundaries and emits UTF-8 shards. */
public final class CsvShardPreprocessor {

    private CsvShardPreprocessor() {
    }

    public static List<ImportManifestShard> preprocess(File source, Charset sourceCharset,
            CSVFormat sourceFormat, Path outputDirectory, String tableName, int layer, long targetBytes)
            throws IOException {
        if (source == null || !source.isFile() || !source.canRead()) {
            throw new IllegalArgumentException("CSV preprocessing requires a readable source file");
        }
        if (sourceCharset == null || sourceFormat == null) {
            throw new IllegalArgumentException("CSV preprocessing requires an explicit charset and dialect");
        }
        if (StringUtils.isBlank(tableName) || layer < 0 || targetBytes <= 0L) {
            throw new IllegalArgumentException("CSV preprocessing requires table, layer and positive shard size");
        }
        Files.createDirectories(outputDirectory);
        List<Path> created = new ArrayList<>();
        List<ImportManifestShard> result = new ArrayList<>();
        ShardWriter current = null;
        try (CSVParser parser = CSVParser.parse(source.toPath(), sourceCharset, sourceFormat)) {
            var records = parser.iterator();
            if (!records.hasNext()) {
                throw new IllegalArgumentException("CSV preprocessing requires a header record");
            }
            List<String> header = new ArrayList<>(records.next().toList());
            if (!header.isEmpty()) {
                header.set(0, StringUtils.removeStart(header.get(0), "\uFEFF"));
            }
            int shardNumber = 0;
            current = openShard(outputDirectory, tableName, layer, shardNumber++, header, created);
            while (records.hasNext()) {
                CSVRecord record = records.next();
                long recordBytes = estimateUtf8Bytes(record);
                if (current.rows > 0L && current.estimatedBytes + recordBytes > targetBytes) {
                    result.add(current.publish());
                    current = openShard(outputDirectory, tableName, layer, shardNumber++, header, created);
                }
                current.write(record, recordBytes);
            }
            result.add(current.publish());
            current = null;
            return List.copyOf(result);
        } catch (Exception failure) {
            if (current != null) {
                current.closeQuietly();
            }
            for (Path path : created) {
                Files.deleteIfExists(path);
                Files.deleteIfExists(partPath(path));
            }
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IOException("CSV preprocessing failed", failure);
        }
    }

    private static ShardWriter openShard(Path outputDirectory, String tableName, int layer, int shardNumber,
            List<String> header, List<Path> created) throws IOException {
        String safeTable = tableName.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        String shardId = safeTable.toLowerCase(Locale.ROOT) + "-" + String.format(Locale.ROOT, "%05d", shardNumber);
        Path target = outputDirectory.resolve(shardId + ".csv").toAbsolutePath().normalize();
        if (!target.startsWith(outputDirectory.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("CSV shard target escapes the staging directory");
        }
        if (Files.exists(target) || Files.exists(partPath(target))) {
            throw new IllegalStateException("CSV shard target already exists: " + target.getFileName());
        }
        created.add(target);
        return new ShardWriter(shardId, tableName.trim(), layer, target, header);
    }

    private static Path partPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    private static long estimateUtf8Bytes(CSVRecord record) {
        long bytes = 1L;
        for (String value : record) {
            bytes = Math.addExact(bytes, value.getBytes(StandardCharsets.UTF_8).length + 3L);
        }
        return bytes;
    }

    private static void updateChecksum(CRC32 checksum, CSVRecord record) {
        for (String value : record) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            byte[] length = Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII);
            checksum.update(length, 0, length.length);
            checksum.update(':');
            checksum.update(bytes, 0, bytes.length);
            checksum.update('|');
        }
        checksum.update('\n');
    }

    static ShardVerification verify(File source, ImportManifestShard shard) throws IOException {
        if (source == null || shard == null || StringUtils.isBlank(shard.getExpectedChecksum())) {
            throw new IllegalArgumentException("CSV shard verification requires a file and expected checksum");
        }
        ShardVerification actual = inspect(source);
        if (actual.rows() != shard.getEstimatedRows()
                || !actual.checksum().equalsIgnoreCase(shard.getExpectedChecksum())) {
            throw new IllegalStateException("CSV shard row count or checksum does not match manifest: "
                    + shard.getShardId());
        }
        return actual;
    }

    static ShardVerification inspect(File source) throws IOException {
        CRC32 checksum = new CRC32();
        long rows = 0L;
        try (CSVParser parser = CSVParser.parse(source.toPath(), StandardCharsets.UTF_8, CSVFormat.DEFAULT)) {
            var records = parser.iterator();
            if (!records.hasNext()) {
                throw new IllegalArgumentException("CSV shard requires a header record");
            }
            records.next();
            while (records.hasNext()) {
                updateChecksum(checksum, records.next());
                rows++;
            }
        }
        return new ShardVerification(rows,
                "CRC32:" + Long.toHexString(checksum.getValue()).toUpperCase(Locale.ROOT));
    }

    record ShardVerification(long rows, String checksum) {
    }

    private static final class ShardWriter {
        private final String shardId;
        private final String tableName;
        private final int layer;
        private final Path target;
        private final Path temporary;
        private final BufferedWriter writer;
        private final CSVPrinter printer;
        private final CRC32 checksum = new CRC32();
        private long rows;
        private long estimatedBytes;
        private boolean closed;

        private ShardWriter(String shardId, String tableName, int layer, Path target, List<String> header)
                throws IOException {
            this.shardId = shardId;
            this.tableName = tableName;
            this.layer = layer;
            this.target = target;
            this.temporary = partPath(target);
            this.writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8);
            this.printer = CSVFormat.DEFAULT.builder().setRecordSeparator("\n").build().print(writer);
            printer.printRecord(header);
            estimatedBytes = header.stream().mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length + 3L)
                    .sum() + 1L;
        }

        private void write(CSVRecord record, long recordBytes) throws IOException {
            printer.printRecord(record);
            updateChecksum(checksum, record);
            estimatedBytes = Math.addExact(estimatedBytes, recordBytes);
            rows++;
        }

        private ImportManifestShard publish() throws IOException {
            close();
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, target);
            }
            return ImportManifestShard.builder()
                    .shardId(shardId)
                    .tableName(tableName)
                    .layer(layer)
                    .sourcePath(target.toString())
                    .estimatedRows(rows)
                    .expectedChecksum("CRC32:" + Long.toHexString(checksum.getValue()).toUpperCase(Locale.ROOT))
                    .dependencyShardIds(List.of())
                    .build();
        }

        private void close() throws IOException {
            if (!closed) {
                closed = true;
                printer.close();
            }
        }

        private void closeQuietly() {
            try {
                close();
            } catch (IOException ignored) {
                // The caller deletes only this invocation's .part and completed shard files.
            }
        }
    }
}

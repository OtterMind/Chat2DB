package ai.chat2db.community.storage.datawiki;

import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocument;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocumentBundle;
import ai.chat2db.community.domain.api.service.storage.IDataWikiDocumentStorage;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LocalDataWikiDocumentStorage implements IDataWikiDocumentStorage {

    private final Path baseDirectory;

    public LocalDataWikiDocumentStorage() {
        this(Path.of(ConfigUtils.getEnvBasePath(), "storage", "datawiki", "wikis"));
    }

    LocalDataWikiDocumentStorage(Path baseDirectory) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
    }

    @Override
    public synchronized String synchronize(String dataWikiId, long revision, List<DataWikiDocument> documents) {
        Path target = wikiDirectory(dataWikiId);
        Path staging = baseDirectory.resolve(".staging-" + dataWikiId + "-" + UUID.randomUUID()).normalize();
        Path backup = baseDirectory.resolve(".backup-" + dataWikiId + "-" + UUID.randomUUID()).normalize();
        try {
            Files.createDirectories(baseDirectory);
            Files.createDirectories(staging);
            List<Map<String, Object>> manifestDocuments = new ArrayList<>();
            for (DataWikiDocument document : documents) {
                Path output = documentPath(staging, document.getPath());
                Files.createDirectories(output.getParent());
                Files.writeString(output, document.getContent(), StandardCharsets.UTF_8);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", document.getPath());
                entry.put("title", document.getTitle());
                entry.put("kind", document.getKind());
                manifestDocuments.add(entry);
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("dataWikiId", dataWikiId);
            manifest.put("revision", revision);
            manifest.put("index", "README.md");
            manifest.put("documents", manifestDocuments);
            Files.writeString(staging.resolve("manifest.json"),
                    JSON.toJSONString(manifest, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);

            if (Files.exists(target)) move(target, backup);
            try {
                move(staging, target);
            } catch (IOException exception) {
                if (Files.exists(backup) && !Files.exists(target)) move(backup, target);
                throw exception;
            }
            deleteTree(backup);
            return target.toString();
        } catch (IOException exception) {
            deleteTreeQuietly(staging);
            deleteTreeQuietly(backup);
            throw new IllegalStateException("failed to synchronize DataWiki documents: " + dataWikiId, exception);
        }
    }

    @Override
    public synchronized DataWikiDocumentBundle load(String dataWikiId, long expectedRevision) {
        Path target = wikiDirectory(dataWikiId);
        Path manifestPath = target.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) return null;
        try {
            DataWikiDocumentBundle bundle = JSON.parseObject(Files.readString(manifestPath), DataWikiDocumentBundle.class);
            if (bundle == null || bundle.getRevision() == null || bundle.getRevision() != expectedRevision) return null;
            bundle.setRootDirectory(target.toString());
            for (DataWikiDocument document : bundle.getDocuments()) {
                document.setContent(null);
                if ("README".equals(document.getKind())) {
                    document.setContent(Files.readString(documentPath(target, document.getPath()), StandardCharsets.UTF_8));
                }
            }
            return bundle;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load DataWiki documents: " + dataWikiId, exception);
        }
    }

    @Override
    public synchronized String read(String dataWikiId, String relativePath) {
        Path target = wikiDirectory(dataWikiId);
        Path manifestPath = target.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) throw new IllegalStateException("DataWiki manifest is missing");
        try {
            DataWikiDocumentBundle bundle = JSON.parseObject(Files.readString(manifestPath), DataWikiDocumentBundle.class);
            boolean declared = bundle != null && bundle.getDocuments().stream()
                    .anyMatch(document -> relativePath.equals(document.getPath()));
            if (!declared) throw new IllegalArgumentException("DataWiki document is not declared in manifest");
            return Files.readString(documentPath(target, relativePath), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read DataWiki document: " + relativePath, exception);
        }
    }

    @Override
    public synchronized void delete(String dataWikiId) {
        Path target = wikiDirectory(dataWikiId);
        try {
            deleteTree(target);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to delete DataWiki documents: " + dataWikiId, exception);
        }
    }

    private Path wikiDirectory(String dataWikiId) {
        if (dataWikiId == null || !dataWikiId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid DataWiki id");
        }
        Path directory = baseDirectory.resolve(dataWikiId).normalize();
        if (!directory.getParent().equals(baseDirectory)) throw new IllegalArgumentException("invalid DataWiki path");
        return directory;
    }

    private static Path documentPath(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("\\")) {
            throw new IllegalArgumentException("invalid DataWiki document path");
        }
        Path relative = Path.of(relativePath);
        Path output = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !output.startsWith(root) || output.equals(root)) {
            throw new IllegalArgumentException("invalid DataWiki document path");
        }
        return output;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // Best-effort cleanup of task-scoped staging content.
        }
    }
}

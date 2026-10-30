package ai.chat2db.community.storage.imports;

import ai.chat2db.community.domain.api.model.imports.ImportTemplate;
import ai.chat2db.community.domain.api.service.imports.IImportTemplateStore;
import ai.chat2db.community.domain.api.service.imports.ImportTemplateFileTypes;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File backed {@link IImportTemplateStore}.
 * <p>
 * Layout: {@code <storage base path>/import-templates/<type>/<file name>.json}, where the type
 * directory is the lower-case file type of the document and the file name is the sanitized template
 * name. A document whose file type is unknown stays in the template root.
 * <p>
 * Every access takes {@link #lock}: a save is a read-modify-write of the template directory and two
 * concurrent saves must not pick the same free file name.
 */
@Slf4j
@Component
public class FileImportTemplateStore implements IImportTemplateStore {

    /** Directory name pieces of the workspace storage location. */
    public static final String STORAGE_DIRECTORY_NAME = "storage";

    public static final String TEMPLATE_DIRECTORY_NAME = "import-templates";

    static final String TEMPLATE_FILE_SUFFIX = ".json";

    static final int MAX_FILE_NAME_LENGTH = 80;

    static final int MAX_FILE_NAME_ATTEMPTS = 1000;

    /**
     * Backup directory written next to the template directory before a legacy document is moved. A
     * timestamp keeps the backups of two migrations apart.
     */
    static final String BACKUP_DIRECTORY_PREFIX = "import-templates-backup-";

    private static final String TEMPORARY_FILE_PREFIX = ".import-templates-";
    private static final String TEMPORARY_FILE_SUFFIX = ".tmp";
    private static final String FILE_NAME_EDGE_CHARACTERS = "-. ";
    private static final Pattern UNSAFE_FILE_NAME_CHARACTERS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]+");
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper;

    private final Path rootDirectory;

    private final Path legacyDirectory;

    private final ReentrantLock lock = new ReentrantLock();

    @Autowired
    public FileImportTemplateStore(ObjectMapper objectMapper) {
        this(objectMapper, defaultRootDirectory(), defaultLegacyDirectory());
    }

    /** Test seam: the shipped locations are derived from the user home, tests pass their own. */
    public FileImportTemplateStore(ObjectMapper objectMapper, Path rootDirectory, Path legacyDirectory) {
        this.objectMapper = objectMapper;
        this.rootDirectory = rootDirectory;
        this.legacyDirectory = legacyDirectory;
    }

    /** The workspace storage location of the templates. */
    public static Path defaultRootDirectory() {
        return Path.of(ConfigUtils.getEnvBasePath(), STORAGE_DIRECTORY_NAME, TEMPLATE_DIRECTORY_NAME);
    }

    /** The location templates were written to before the workspace storage directory carried them. */
    public static Path defaultLegacyDirectory() {
        return Path.of(ConfigUtils.getBasePath(), TEMPLATE_DIRECTORY_NAME);
    }

    @PostConstruct
    public void prepareRootDirectory() {
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create import template directory: " + rootDirectory, e);
        }
    }

    @Override
    public List<ImportTemplate> read() {
        lock.lock();
        try {
            return templatesOf(readStoredTemplates(null));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ImportTemplate> read(String fileType) {
        lock.lock();
        try {
            return templatesOf(readStoredTemplates(fileType));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void save(ImportTemplate template) {
        lock.lock();
        try {
            String name = template.getName() == null ? null : template.getName().trim();
            Path directory = templateDirectory(template.getFileType());
            Optional<Path> existing = storedTemplateFile(name);
            // The name identifies the template, the file type owns the directory: a preset stored
            // under another type moves into the directory of the type it is saved as.
            Path target = existing
                    .filter(file -> Objects.equals(file.getParent(), directory))
                    .orElseGet(() -> availableTemplateFile(directory, name));
            writeTemplate(target, template);
            existing.filter(file -> !file.equals(target)).ifPresent(this::deleteTemplateFile);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void delete(String name, String fileType) {
        lock.lock();
        try {
            if (StringUtils.isNotBlank(name)) {
                storedTemplateFile(name.trim())
                        .or(() -> sanitizedTemplateFile(name, fileType))
                        .ifPresent(this::deleteTemplateFile);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String sanitizedFileName(String name) {
        if (name == null) {
            return null;
        }
        String sanitized = UNSAFE_FILE_NAME_CHARACTERS.matcher(name.trim()).replaceAll("-");
        sanitized = stripEdgeCharacters(sanitized);
        if (sanitized.length() > MAX_FILE_NAME_LENGTH) {
            sanitized = stripEdgeCharacters(sanitized.substring(0, MAX_FILE_NAME_LENGTH));
        }
        return sanitized.isEmpty() ? null : sanitized;
    }

    /**
     * One pass over the legacy location: every readable document moves to the directory of its file
     * type, a document that names no type but carries an option group is rewritten with the type that
     * group implies, and every document is copied to a backup directory first.
     */
    @Override
    public int migrateLegacyTemplates() {
        lock.lock();
        try {
            if (!Files.isDirectory(legacyDirectory) || sameDirectory(legacyDirectory, rootDirectory)) {
                return 0;
            }
            List<Path> files = legacyTemplateFiles();
            if (files.isEmpty()) {
                return 0;
            }
            Path backupDirectory;
            try {
                backupDirectory = createBackupDirectory();
            } catch (IOException e) {
                // Moving without a backup could lose a template, so the migration waits for a run that
                // can write one.
                log.warn("Keeping {} legacy import template(s) in {}: no backup directory is available",
                        files.size(), legacyDirectory, e);
                return 0;
            }
            int moved = 0;
            for (Path file : files) {
                if (migrateLegacyTemplate(file, backupDirectory)) {
                    moved++;
                }
            }
            log.info("Moved {} of {} legacy import template(s) into {}", moved, files.size(), rootDirectory);
            return moved;
        } finally {
            lock.unlock();
        }
    }

    /** The template root plus every type directory, or only the requested type directory. */
    private List<Path> readDirectories(String fileType) {
        if (StringUtils.isBlank(fileType)) {
            List<Path> directories = new ArrayList<>();
            directories.add(rootDirectory);
            ImportTemplateFileTypes.directoryNames()
                    .forEach(directoryName -> directories.add(rootDirectory.resolve(directoryName)));
            return directories;
        }
        String directoryName = ImportTemplateFileTypes.directoryName(fileType);
        return directoryName == null ? List.of() : List.of(rootDirectory.resolve(directoryName));
    }

    /**
     * Reads every stored template of the requested file type, in directory order. A file type narrows
     * the read to the directory of that type; without one the template root and every type directory
     * are read.
     */
    private List<StoredTemplate> readStoredTemplates(String fileType) {
        List<StoredTemplate> stored = new ArrayList<>();
        for (Path directory : readDirectories(fileType)) {
            stored.addAll(readTemplatesIn(directory));
        }
        return stored;
    }

    private List<StoredTemplate> readTemplatesIn(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<StoredTemplate> stored = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(this::isTemplateFile).sorted().forEach(file -> {
                ImportTemplate template = readTemplate(file);
                if (template != null) {
                    stored.add(new StoredTemplate(file, template));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the import template directory " + directory, e);
        }
        return stored;
    }

    /** Only visible JSON documents are templates; temporary and hidden files are not listed. */
    private boolean isTemplateFile(Path file) {
        Path fileName = file.getFileName();
        if (fileName == null || !Files.isRegularFile(file)) {
            return false;
        }
        String name = fileName.toString();
        return name.endsWith(TEMPLATE_FILE_SUFFIX) && !name.startsWith(".")
                && !name.startsWith(TEMPORARY_FILE_PREFIX);
    }

    /**
     * One damaged file only removes its own template from the list: the other presets stay usable,
     * and the file is left untouched so the user can inspect or repair it.
     */
    // impl-contract: fallback - a single unreadable template file must not fail the whole list.
    private ImportTemplate readTemplate(Path file) {
        try {
            ImportTemplate template = objectMapper.readValue(file.toFile(), ImportTemplate.class);
            if (template == null || StringUtils.isBlank(template.getName())) {
                log.warn("Skipping import template file without a name: {}", file);
                return null;
            }
            // The name is the identity a save and a delete match on, so it is read in its stored form.
            template.setName(template.getName().trim());
            return template;
        } catch (IOException | RuntimeException e) {
            log.warn("Skipping unreadable import template file {}", file, e);
            return null;
        }
    }

    /** The file that already holds this template name, if any. */
    private Optional<Path> storedTemplateFile(String name) {
        return readStoredTemplates(null).stream()
                .filter(entry -> Objects.equals(entry.template().getName(), name))
                .map(StoredTemplate::file)
                .findFirst();
    }

    /**
     * The file a template name occupies, used when no readable document carries that name: the
     * directory of the given file type comes first, then every other template directory, then the
     * root. A document whose options were edited by hand, or one that cannot be read at all, is
     * still reachable by name.
     */
    private Optional<Path> sanitizedTemplateFile(String name, String fileType) {
        String fileName = sanitizedFileName(name);
        if (fileName == null) {
            return Optional.empty();
        }
        Path preferred = templateDirectory(fileType);
        List<Path> candidates = new ArrayList<>();
        candidates.add(preferred);
        ImportTemplateFileTypes.directoryNames().stream()
                .map(rootDirectory::resolve)
                .filter(directory -> !directory.equals(preferred))
                .forEach(candidates::add);
        if (!rootDirectory.equals(preferred)) {
            candidates.add(rootDirectory);
        }
        return candidates.stream()
                .map(directory -> resolveTemplateFile(directory, fileName + TEMPLATE_FILE_SUFFIX))
                .filter(Files::isRegularFile)
                .findFirst();
    }

    /** The directory a file type is stored in; an unknown type stays in the template root. */
    private Path templateDirectory(String fileType) {
        String directoryName = ImportTemplateFileTypes.directoryName(fileType);
        return directoryName == null ? rootDirectory : rootDirectory.resolve(directoryName);
    }

    /**
     * A free file inside one template directory. Two template names can sanitize to the same file
     * name; in that case the second template gets a numbered suffix, and the name inside the file
     * stays authoritative.
     */
    private Path availableTemplateFile(Path directory, String name) {
        String baseName = sanitizedFileName(name);
        if (baseName == null) {
            throw new IllegalArgumentException("Template name leaves no usable file name: " + name);
        }
        for (int attempt = 1; attempt <= MAX_FILE_NAME_ATTEMPTS; attempt++) {
            String candidate = attempt == 1 ? baseName : baseName + "-" + attempt;
            Path file = resolveTemplateFile(directory, candidate + TEMPLATE_FILE_SUFFIX);
            if (!Files.exists(file)) {
                return file;
            }
        }
        throw new IllegalStateException("No free template file name for " + name);
    }

    private String stripEdgeCharacters(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && FILE_NAME_EDGE_CHARACTERS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && FILE_NAME_EDGE_CHARACTERS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** Resolves one file name inside one template directory and refuses to leave it. */
    Path resolveTemplateFile(Path directory, String fileName) {
        Path parent = directory.toAbsolutePath().normalize();
        Path file = parent.resolve(fileName).normalize();
        if (!parent.equals(file.getParent())) {
            throw new IllegalArgumentException("Template file name leaves its directory: " + fileName);
        }
        return file;
    }

    private void writeTemplate(Path target, ImportTemplate template) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), TEMPORARY_FILE_PREFIX, TEMPORARY_FILE_SUFFIX);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), template);
            replaceTemplateFile(temporary, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write the import template " + target, e);
        } finally {
            deleteTemporaryFile(temporary);
        }
    }

    /** Replaces one template file in a single step; a filesystem without atomic rename falls back. */
    void replaceTemplateFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTemplateFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete the import template " + file, e);
        }
    }

    private void deleteTemporaryFile(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException e) {
            log.warn("Failed to clean up temporary import template file {}", temporary, e);
        }
    }

    private List<Path> legacyTemplateFiles() {
        try (Stream<Path> tree = Files.walk(legacyDirectory)) {
            return tree.filter(this::isTemplateFile).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Failed to list the legacy import templates in {}", legacyDirectory, e);
            return List.of();
        }
    }

    /** Moves one legacy document; a document that stays behind is reported and never dropped. */
    private boolean migrateLegacyTemplate(Path file, Path backupDirectory) {
        try {
            ImportTemplate template = readTemplate(file);
            String fileType = template == null
                    ? null : ImportTemplateFileTypes.resolve(template.getFileType(), template.getFormatOptions());
            Path target = legacyTarget(file, fileType);
            if (Files.exists(target)) {
                backupLegacyTemplate(file, backupDirectory);
                log.warn("Keeping the legacy import template {} in {}: {} already exists", file, legacyDirectory,
                        target);
                return false;
            }
            backupLegacyTemplate(file, backupDirectory);
            Files.createDirectories(target.getParent());
            moveTemplateFile(file, target);
            if (template != null && !Objects.equals(template.getFileType(), fileType)) {
                // The document was written before templates recorded their type, so it is rewritten
                // with the type its option group implies; the content stays the authority.
                template.setFileType(fileType);
                writeTemplate(target, template);
            }
            log.info("Moved legacy import template {} to {}", file, target);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to migrate the legacy import template {}", file, e);
            return false;
        }
    }

    /**
     * Where one legacy document belongs: the directory of its file type when that is known, otherwise
     * its own place below the template root.
     */
    private Path legacyTarget(Path file, String fileType) {
        if (fileType != null) {
            return templateDirectory(fileType).resolve(file.getFileName());
        }
        return rootDirectory.resolve(relativeToLegacyDirectory(file));
    }

    /** Moves one template file in a single step; a filesystem without atomic rename falls back. */
    void moveTemplateFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void backupLegacyTemplate(Path file, Path backupDirectory) throws IOException {
        Path target = resolveBelow(backupDirectory, relativeToLegacyDirectory(file));
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Resolves a relative path below one directory and refuses to leave it. */
    Path resolveBelow(Path directory, Path relative) {
        Path parent = directory.toAbsolutePath().normalize();
        Path file = parent.resolve(relative).normalize();
        if (file.equals(parent) || !file.startsWith(parent)) {
            throw new IllegalArgumentException("Import template path leaves its directory: " + relative);
        }
        return file;
    }

    private Path relativeToLegacyDirectory(Path file) {
        return legacyDirectory.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
    }

    /**
     * A backup directory next to the template directory, or one in the temporary directory when that
     * location cannot be written.
     */
    private Path createBackupDirectory() throws IOException {
        String name = BACKUP_DIRECTORY_PREFIX + BACKUP_TIMESTAMP.format(Instant.now());
        Path parent = rootDirectory.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            try {
                return Files.createDirectories(parent.resolve(name));
            } catch (IOException e) {
                log.warn("Falling back to the temporary directory for the import template backup", e);
            }
        }
        return Files.createTempDirectory(BACKUP_DIRECTORY_PREFIX);
    }

    private boolean sameDirectory(Path first, Path second) {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
    }

    private List<ImportTemplate> templatesOf(List<StoredTemplate> stored) {
        return stored.stream().map(StoredTemplate::template).collect(Collectors.toList());
    }

    /** One template file on disk together with the template it holds. */
    private record StoredTemplate(Path file, ImportTemplate template) {
    }
}

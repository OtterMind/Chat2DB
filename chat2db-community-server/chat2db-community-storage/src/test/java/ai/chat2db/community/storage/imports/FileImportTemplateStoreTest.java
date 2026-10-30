package ai.chat2db.community.storage.imports;

import ai.chat2db.community.domain.api.model.imports.ImportTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileImportTemplateStoreTest {

    private static final long CREATED_AT = 1700000000000L;

    @TempDir
    Path tempDirectory;

    private ObjectMapper objectMapper;

    private Path storageDirectory;

    private Path legacyDirectory;

    private FileImportTemplateStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        storageDirectory = tempDirectory.resolve("storage").resolve(FileImportTemplateStore.TEMPLATE_DIRECTORY_NAME);
        legacyDirectory = tempDirectory.resolve("legacy").resolve(FileImportTemplateStore.TEMPLATE_DIRECTORY_NAME);
        store = store();
    }

    @Test
    void writesOneFilePerTemplateUnderItsFileTypeDirectory() throws IOException {
        store.save(template("e2e-ok", "CSV", csvOptions("UTF-8", ";")));
        store.save(template("orders", "JSON", jsonOptions("GBK")));

        JsonNode document = objectMapper.readTree(storageDirectory.resolve("csv/e2e-ok.json").toFile());
        assertAll(
                () -> assertEquals(Set.of("csv/e2e-ok.json", "json/orders.json"), storedFileNames()),
                () -> assertEquals("e2e-ok", document.path("name").asText()),
                () -> assertEquals(CREATED_AT, document.path("createdAt").asLong()),
                () -> assertEquals("CSV", document.path("fileType").asText()),
                () -> assertEquals(";", document.path("formatOptions").path("csvOptions").path("delimiter").asText())
        );
    }

    @Test
    void keepsADocumentWithoutAFileTypeInTheTemplateRoot() {
        store.save(template("mapping-era", null, Map.of("columnMappings", Map.of("id", "user_id"))));

        assertAll(
                () -> assertEquals(Set.of("mapping-era.json"), storedFileNames()),
                () -> assertEquals(List.of("mapping-era"), names(store.read())),
                () -> assertNull(store.read().get(0).getFileType())
        );
    }

    @Test
    void laysTheTemplatesOutUnderTheWorkspaceStorageDirectory() {
        Path root = FileImportTemplateStore.defaultRootDirectory();

        assertAll(
                () -> assertTrue(root.endsWith(Path.of("storage", "import-templates")), root.toString()),
                () -> assertEquals("import-templates", root.getFileName().toString())
        );
    }

    @Test
    void readsEveryFileTypeOrOnlyTheRequestedOne() {
        store.save(template("csv-preset", "CSV", csvOptions("UTF-8", ",")));
        store.save(template("json-preset", "JSON", jsonOptions("UTF-8")));

        assertAll(
                () -> assertEquals(List.of("csv-preset", "json-preset"), names(store.read())),
                () -> assertEquals(List.of("csv-preset"), names(store.read("CSV"))),
                () -> assertEquals(List.of("csv-preset"), names(store.read(" csv "))),
                () -> assertEquals(List.of("json-preset"), names(store.read("JSON"))),
                () -> assertEquals(List.of(), names(store.read("SQL")))
        );
    }

    @Test
    void skipsOnlyTheUnreadableFileAndLeavesItUntouched() throws IOException {
        store.save(template("good", "CSV", csvOptions("UTF-8", ",")));
        Path damaged = storageDirectory.resolve("csv/damaged.json");
        Files.writeString(damaged, "{\"version\":1,\"name\":\"damaged\"", StandardCharsets.UTF_8);

        Path nameless = storageDirectory.resolve("csv/nameless.json");
        Files.writeString(nameless, "{\"version\":1,\"formatOptions\":{}}", StandardCharsets.UTF_8);

        List<ImportTemplate> stored = store.read();

        assertAll(
                () -> assertEquals(List.of("good"), names(stored)),
                () -> assertEquals("{\"version\":1,\"name\":\"damaged\"",
                        Files.readString(damaged, StandardCharsets.UTF_8)),
                () -> assertTrue(Files.exists(damaged)),
                // A document without a name is not a template either.
                () -> assertTrue(Files.exists(nameless))
        );
    }

    @Test
    void ignoresTemporaryHiddenAndForeignFiles() throws IOException {
        store.save(template("visible", "CSV", csvOptions("UTF-8", ",")));
        Files.writeString(storageDirectory.resolve(".hidden.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(storageDirectory.resolve(".import-templates-42.tmp"), "{}", StandardCharsets.UTF_8);
        Files.writeString(storageDirectory.resolve("notes.txt"), "{}", StandardCharsets.UTF_8);
        Files.createDirectories(storageDirectory.resolve("not-a-type-directory"));
        Files.writeString(storageDirectory.resolve("not-a-type-directory/other.json"),
                "{\"version\":1,\"name\":\"other\",\"formatOptions\":{}}", StandardCharsets.UTF_8);

        assertEquals(List.of("visible"), names(store.read()));
    }

    @Test
    void sanitizesTemplateNamesIntoSafeFileNames() {
        store.save(template("../escaped", "CSV", csvOptions("UTF-8", ",")));
        store.save(template("bench 表/月度", "CSV", csvOptions("UTF-8", ",")));

        assertAll(
                () -> assertEquals(Set.of("csv/escaped.json", "csv/bench 表-月度.json"), storedFileNames()),
                () -> assertFalse(Files.exists(tempDirectory.resolve("escaped.json"))),
                () -> assertEquals("escaped", store.sanitizedFileName("../escaped")),
                () -> assertEquals("bench 表-月度", store.sanitizedFileName(" bench 表/月度 ")),
                () -> assertEquals(FileImportTemplateStore.MAX_FILE_NAME_LENGTH,
                        store.sanitizedFileName("t".repeat(200)).length()),
                () -> assertNull(store.sanitizedFileName(" /// ")),
                () -> assertNull(store.sanitizedFileName("   ")),
                () -> assertNull(store.sanitizedFileName(null))
        );
    }

    @Test
    void refusesAFileNameThatWouldLeaveTheTemplateDirectory() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> store.resolveTemplateFile(storageDirectory.resolve("csv"), "../escaped.json")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> store.resolveBelow(storageDirectory, Path.of("..", "escaped.json"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> store.save(template("///", "CSV", csvOptions("UTF-8", ","))))
        );
    }

    @Test
    void replacesTheSameNameAndMovesItToTheNewFileTypeDirectory() throws IOException {
        store.save(template("shared", "CSV", csvOptions("UTF-8", ",")));
        store.save(template("other", "CSV", csvOptions("UTF-8", ",")));
        store.save(template("shared", "CSV", csvOptions("GBK", ";")));

        assertAll(
                () -> assertEquals(Set.of("csv/shared.json", "csv/other.json"), storedFileNames()),
                () -> assertEquals("GBK", csvOptionsOf(store.read(), "shared").get("encoding"))
        );

        store.save(template("shared", "JSON", jsonOptions("UTF-8")));

        assertAll(
                () -> assertEquals(Set.of("json/shared.json", "csv/other.json"), storedFileNames()),
                () -> assertEquals(1, store.read().stream().filter(entry -> "shared".equals(entry.getName())).count()),
                () -> assertEquals("JSON", store.read("JSON").get(0).getFileType())
        );
    }

    @Test
    void numbersFileNamesThatCollideAfterSanitizing() throws IOException {
        store.save(template("a/b", "CSV", csvOptions("UTF-8", ",")));
        store.save(template("a-b", "CSV", csvOptions("GBK", ";")));

        assertAll(
                () -> assertEquals(Set.of("csv/a-b.json", "csv/a-b-2.json"), storedFileNames()),
                // Saving the first template again keeps its own file; the colliding file is untouched.
                () -> assertEquals("a-b", objectMapper.readTree(storageDirectory.resolve("csv/a-b-2.json").toFile())
                        .path("name").asText())
        );

        store.save(template("a/b", "CSV", csvOptions("GB18030", ",")));

        assertAll(
                () -> assertEquals(Set.of("csv/a-b.json", "csv/a-b-2.json"), storedFileNames()),
                () -> assertEquals("GB18030",
                        objectMapper.readTree(storageDirectory.resolve("csv/a-b.json").toFile())
                                .path("formatOptions").path("csvOptions").path("encoding").asText()),
                () -> assertEquals("GBK",
                        objectMapper.readTree(storageDirectory.resolve("csv/a-b-2.json").toFile())
                                .path("formatOptions").path("csvOptions").path("encoding").asText())
        );
    }

    @Test
    void deletesByNameAndFallsBackToTheTypeDirectory() throws IOException {
        store.save(template("keep", "CSV", csvOptions("UTF-8", ",")));
        store.save(template("drop", "CSV", csvOptions("UTF-8", ",")));
        store.save(template("moved", "CSV", csvOptions("UTF-8", ",")));
        Files.createDirectories(storageDirectory.resolve("json"));
        Files.move(storageDirectory.resolve("csv/moved.json"), storageDirectory.resolve("json/moved.json"));

        store.delete("drop", null);
        store.delete("moved", "CSV");
        store.delete("  ", "CSV");

        assertAll(
                () -> assertEquals(Set.of("csv/keep.json"), storedFileNames()),
                () -> assertEquals(List.of("keep"), names(store.read()))
        );

        // A document that cannot be read is still reachable by its sanitized file name.
        Path damaged = storageDirectory.resolve("json/broken-name.json");
        Files.writeString(damaged, "{\"version\":1,\"name\":\"broken-name\"", StandardCharsets.UTF_8);
        store.delete("broken-name", null);

        assertFalse(Files.exists(damaged));
    }

    @Test
    void keepsThePreviousFileAndCleansUpTheTemporaryFileWhenTheReplaceFails() throws IOException {
        store.save(template("before", "CSV", csvOptions("UTF-8", ",")));
        Path storedFile = storageDirectory.resolve("csv/before.json");
        byte[] original = Files.readAllBytes(storedFile);

        FileImportTemplateStore failing = new FailingFileImportTemplateStore();

        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> failing.save(template("before", "CSV", csvOptions("GBK", ";"))));

        assertAll(
                () -> assertEquals("forced replace failure", failure.getCause().getMessage()),
                () -> assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(storedFile))),
                () -> assertEquals("UTF-8", csvOptionsOf(store.read(), "before").get("encoding")),
                () -> assertEquals(0, temporaryFileCount())
        );
    }

    @Test
    void movesTheLegacyTemplatesKeepingEveryDocumentAndBackingThemUp() throws IOException {
        writeLegacy("csv/e2e-csv-live.json", document("e2e-csv-live", "CSV", csvOptions("UTF-8", ",")));
        writeLegacy("json/e2e-json-live.json", document("e2e-json-live", "JSON", jsonOptions("UTF-8")));
        writeLegacy("root-csv.json", document("root-csv", null, csvOptions("UTF-8", ";")));
        writeLegacy("unknown.json", document("unknown", null, Map.of("columnMappings", Map.of("id", "user_id"))));
        Path damaged = writeLegacyText("damaged.json", "{\"version\":1,\"name\":\"damaged\"");

        int moved = store.migrateLegacyTemplates();

        assertAll(
                () -> assertEquals(5, moved),
                () -> assertEquals(Set.of("csv/e2e-csv-live.json", "json/e2e-json-live.json", "csv/root-csv.json",
                        "unknown.json", "damaged.json"), storedFileNames()),
                // A document that carried no file type is filed under the type its options imply.
                () -> assertEquals("CSV", objectMapper.readTree(storageDirectory.resolve("csv/root-csv.json").toFile())
                        .path("fileType").asText()),
                () -> assertFalse(objectMapper.readTree(storageDirectory.resolve("unknown.json").toFile())
                        .has("fileType"), "an unknown type must not be invented"),
                () -> assertEquals("{\"version\":1,\"name\":\"damaged\"",
                        Files.readString(storageDirectory.resolve("damaged.json"), StandardCharsets.UTF_8)),
                () -> assertFalse(Files.exists(damaged)),
                // The legacy directory itself stays, only its files moved away.
                () -> assertTrue(Files.isDirectory(legacyDirectory)),
                () -> assertEquals(Set.of(), legacyFileNames()),
                () -> assertEquals(Set.of("csv/e2e-csv-live.json", "json/e2e-json-live.json", "root-csv.json",
                        "unknown.json", "damaged.json"), backupFileNames()),
                // Nothing is left to move, so a later run is a no-op.
                () -> assertEquals(0, store().migrateLegacyTemplates())
        );
    }

    @Test
    void keepsALegacyDocumentWhoseTargetAlreadyExists() throws IOException {
        store.save(template("kept", "CSV", csvOptions("UTF-8", ",")));
        Path legacy = writeLegacy("csv/kept.json", document("kept", "CSV", csvOptions("GBK", ";")));

        assertEquals(0, store.migrateLegacyTemplates());

        assertAll(
                () -> assertEquals("UTF-8", csvOptionsOf(store.read(), "kept").get("encoding")),
                () -> assertTrue(Files.exists(legacy)),
                () -> assertEquals("GBK", objectMapper.readTree(legacy.toFile())
                        .path("formatOptions").path("csvOptions").path("encoding").asText()),
                // The skipped document still reaches the backup, so nothing is lost.
                () -> assertEquals(Set.of("csv/kept.json"), backupFileNames())
        );
    }

    @Test
    void readsAnEmptyOrMissingDirectoryAsNoTemplates() {
        assertAll(
                () -> assertEquals(List.of(), store.read()),
                () -> assertEquals(List.of(), store.read("CSV")),
                () -> assertFalse(Files.exists(storageDirectory)),
                () -> assertEquals(0, store.migrateLegacyTemplates())
        );
    }

    private FileImportTemplateStore store() {
        return new FileImportTemplateStore(objectMapper, storageDirectory, legacyDirectory);
    }

    private ImportTemplate template(String name, String fileType, Map<String, Object> formatOptions) {
        ImportTemplate template = new ImportTemplate();
        template.setVersion(1);
        template.setName(name);
        template.setCreatedAt(CREATED_AT);
        template.setFileType(fileType);
        template.setFormatOptions(formatOptions);
        return template;
    }

    private Map<String, Object> document(String name, String fileType, Map<String, Object> formatOptions) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", 1);
        document.put("name", name);
        document.put("createdAt", CREATED_AT);
        if (fileType != null) {
            document.put("fileType", fileType);
        }
        document.put("formatOptions", formatOptions);
        return document;
    }

    private Map<String, Object> csvOptions(String encoding, String delimiter) {
        return Map.of("csvOptions", Map.of("encoding", encoding, "delimiter", delimiter, "hasHeader", true));
    }

    private Map<String, Object> jsonOptions(String encoding) {
        return Map.of("jsonOptions", Map.of("encoding", encoding, "structure", "ARRAY"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> csvOptionsOf(List<ImportTemplate> templates, String name) {
        ImportTemplate template = templates.stream()
                .filter(candidate -> name.equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        return (Map<String, Object>) template.getFormatOptions().get("csvOptions");
    }

    private Path writeLegacy(String relative, Map<String, Object> document) throws IOException {
        Path file = legacyDirectory.resolve(relative);
        Files.createDirectories(file.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), document);
        return file;
    }

    private Path writeLegacyText(String relative, String content) throws IOException {
        Path file = legacyDirectory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private List<String> names(List<ImportTemplate> templates) {
        return templates.stream().map(ImportTemplate::getName).collect(Collectors.toList());
    }

    private Set<String> storedFileNames() {
        return new LinkedHashSet<>(relativeFileNames(storageDirectory, file -> true));
    }

    private Set<String> legacyFileNames() {
        return new LinkedHashSet<>(relativeFileNames(legacyDirectory, file -> true));
    }

    private Set<String> backupFileNames() {
        try (Stream<Path> entries = Files.list(storageDirectory.getParent())) {
            Path backup = entries.filter(Files::isDirectory)
                    .filter(directory -> directory.getFileName().toString()
                            .startsWith(FileImportTemplateStore.BACKUP_DIRECTORY_PREFIX))
                    .findFirst()
                    .orElseThrow();
            return new LinkedHashSet<>(relativeFileNames(backup, file -> true));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> relativeFileNames(Path directory, java.util.function.Predicate<Path> filter) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(filter)
                    .map(file -> directory.relativize(file).toString().replace(File.separatorChar, '/'))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long temporaryFileCount() throws IOException {
        try (Stream<Path> files = Files.walk(storageDirectory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().startsWith(".import-templates-"))
                    .count();
        }
    }

    private final class FailingFileImportTemplateStore extends FileImportTemplateStore {

        FailingFileImportTemplateStore() {
            super(objectMapper, storageDirectory, legacyDirectory);
        }

        @Override
        void replaceTemplateFile(Path temporary, Path target) throws IOException {
            throw new IOException("forced replace failure");
        }
    }
}

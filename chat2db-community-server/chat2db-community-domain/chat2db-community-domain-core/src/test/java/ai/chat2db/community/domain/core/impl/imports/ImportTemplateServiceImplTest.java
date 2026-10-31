package ai.chat2db.community.domain.core.impl.imports;

import ai.chat2db.community.domain.api.model.imports.ImportTemplate;
import ai.chat2db.community.domain.api.service.imports.IImportTemplateStore;
import ai.chat2db.community.domain.core.converter.ImportTemplateConverter;
import ai.chat2db.community.storage.imports.FileImportTemplateStore;
import ai.chat2db.community.tools.exception.BusinessException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportTemplateServiceImplTest {

    private static final long CREATED_AT = 1700000000000L;

    @TempDir
    Path tempDirectory;

    private ObjectMapper objectMapper;

    /** Where the store keeps the templates now. */
    private Path storageDirectory;

    /** Where the templates were kept before the store moved under the workspace storage directory. */
    private Path legacyDirectory;

    private ImportTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        storageDirectory = tempDirectory.resolve("storage").resolve(FileImportTemplateStore.TEMPLATE_DIRECTORY_NAME);
        legacyDirectory = tempDirectory.resolve("legacy").resolve(FileImportTemplateStore.TEMPLATE_DIRECTORY_NAME);
        service = service();
    }

    @Test
    void storesOneFilePerTemplateUnderItsFileTypeWithOnlyTheTemplateFields() throws IOException {
        service.upsert(template("e2e-ok", "CSV", csvOptions("UTF-8", ";", 2)));
        service.upsert(template("orders", "CSV", csvOptions("GBK", ",", 1)));

        assertEquals(Set.of("csv/e2e-ok.json", "csv/orders.json"), templateFileNames());
        JsonNode document = objectMapper.readTree(storageDirectory.resolve("csv/e2e-ok.json").toFile());
        assertAll(
                () -> assertEquals(Set.of("version", "name", "createdAt", "fileType", "formatOptions"),
                        fieldNames(document)),
                () -> assertEquals(ImportTemplateServiceImpl.STORAGE_VERSION, document.path("version").asInt()),
                () -> assertEquals("e2e-ok", document.path("name").asText()),
                () -> assertEquals(CREATED_AT, document.path("createdAt").asLong()),
                () -> assertEquals("CSV", document.path("fileType").asText()),
                () -> assertEquals("UTF-8", document.path("formatOptions").path("csvOptions").path("encoding").asText()),
                () -> assertEquals(";", document.path("formatOptions").path("csvOptions").path("delimiter").asText()),
                () -> assertEquals(2, document.path("formatOptions").path("csvOptions").path("headerRow").asInt())
        );
    }

    @Test
    void storesEachFileTypeInItsOwnDirectory() {
        service.upsert(template("csv-preset", "CSV", csvOptions("UTF-8", ";", 2)));
        service.upsert(template("json-preset", "JSON", jsonOptions("UTF-8")));
        service.upsert(template("xls-preset", "XLS", excelOptions()));
        service.upsert(template("xlsx-preset", "XLSX", excelOptions()));

        assertAll(
                () -> assertEquals(Set.of("csv/csv-preset.json", "json/json-preset.json", "xls/xls-preset.json",
                        "xlsx/xlsx-preset.json"), templateFileNames()),
                // Same instant for all four, so the name breaks the tie in the list.
                () -> assertEquals(List.of("CSV", "JSON", "XLS", "XLSX"), fileTypesOf(service().list()))
        );
    }

    @Test
    void roundTripsTheTemplateThroughAFreshInstance() {
        service.upsert(template("e2e-ok", "CSV", csvOptions("UTF-8", ";", 2)));

        List<ImportTemplate> stored = service().list();

        assertEquals(1, stored.size());
        assertAll(
                () -> assertEquals("e2e-ok", stored.get(0).getName()),
                () -> assertEquals(CREATED_AT, stored.get(0).getCreatedAt()),
                () -> assertEquals("CSV", stored.get(0).getFileType()),
                () -> assertEquals("UTF-8", csvOptionsOf(stored.get(0)).get("encoding")),
                () -> assertEquals(";", csvOptionsOf(stored.get(0)).get("delimiter")),
                () -> assertEquals(2, csvOptionsOf(stored.get(0)).get("headerRow"))
        );
    }

    @Test
    void listsOnlyTheRequestedFileType() {
        service.upsert(template("csv-preset", "CSV", csvOptions("UTF-8", ";", 2)));
        service.upsert(template("json-preset", "JSON", jsonOptions("UTF-8")));

        assertAll(
                () -> assertEquals(List.of("csv-preset", "json-preset"), names(service().list())),
                () -> assertEquals(List.of("csv-preset"), names(service().list("CSV"))),
                () -> assertEquals(List.of("json-preset"), names(service().list("JSON"))),
                () -> assertEquals(List.of("csv-preset"), names(service().list(" csv "))),
                () -> assertEquals(List.of(), names(service().list("SQL"))),
                () -> assertEquals(List.of(), names(service().list("no-such-type")))
        );
    }

    @Test
    void keepsTheWholeFormatOptionGroupIncludingNestedValues() {
        Map<String, Object> options = Map.of(
                "csvOptions", Map.of(
                        "encoding", "GB18030",
                        "delimiter", "\t",
                        "quote", "'",
                        "escape", "\\",
                        "hasHeader", true,
                        "headerRow", 3,
                        "dataStartRow", 4,
                        "dataEndRow", 900,
                        "dateOrder", "DMY",
                        "decimalSymbol", ","));

        service.upsert(template("full-options", options));

        ImportTemplate stored = service().list().get(0);
        assertAll(
                () -> assertEquals(options, stored.getFormatOptions()),
                // A request that omits the file type still lands under the type its options imply.
                () -> assertEquals("CSV", stored.getFileType())
        );
    }

    @Test
    void keepsOneTemplatePerNameAndReplacesTheStoredOne() throws IOException {
        service.upsert(template("shared", "CSV", csvOptions("UTF-8", ",", 1)));
        service.upsert(template("other", "CSV", csvOptions("UTF-8", ",", 1)));
        service.upsert(template("shared", "CSV", csvOptions("GBK", ";", 5)));

        assertEquals(Set.of("csv/shared.json", "csv/other.json"), templateFileNames());
        List<ImportTemplate> stored = service().list();
        assertEquals(2, stored.size());
        ImportTemplate shared = stored.stream()
                .filter(candidate -> "shared".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("GBK", csvOptionsOf(shared).get("encoding"));
        assertEquals(";", csvOptionsOf(shared).get("delimiter"));
        assertTrue(Files.readString(storageDirectory.resolve("csv/shared.json"), StandardCharsets.UTF_8)
                .contains("GBK"));
    }

    @Test
    void savingTheSameNameUnderAnotherFileTypeMovesTheTemplate() {
        service.upsert(template("swap", "CSV", csvOptions("UTF-8", ",", 1)));
        service.upsert(template("swap", "JSON", jsonOptions("UTF-8")));

        List<ImportTemplate> stored = service().list();
        assertAll(
                () -> assertEquals(Set.of("json/swap.json"), templateFileNames()),
                () -> assertEquals(1, stored.size()),
                () -> assertEquals("JSON", stored.get(0).getFileType()),
                () -> assertTrue(stored.get(0).getFormatOptions().containsKey("jsonOptions"))
        );
    }

    @Test
    void adoptsTheLegacyDocumentsIntoTheStorageDirectoryAndRecordsTheirType() throws IOException {
        Path legacy = writeLegacyTemplate("e2e-csv-preset.json", "e2e-csv-preset", csvOptions("UTF-8", ";", 2));
        Path damaged = writeLegacyText("damaged.json", "{\"version\":1,\"name\":\"damaged\"");

        List<ImportTemplate> stored = service().list();

        JsonNode moved = objectMapper.readTree(storageDirectory.resolve("csv/e2e-csv-preset.json").toFile());
        assertAll(
                () -> assertEquals(List.of("e2e-csv-preset"), names(stored)),
                () -> assertEquals("CSV", stored.get(0).getFileType()),
                () -> assertFalse(Files.exists(legacy), "the legacy document must leave the legacy location"),
                () -> assertEquals("CSV", moved.path("fileType").asText()),
                () -> assertEquals("e2e-csv-preset", moved.path("name").asText()),
                () -> assertEquals(CREATED_AT, moved.path("createdAt").asLong()),
                () -> assertEquals(";", moved.path("formatOptions").path("csvOptions").path("delimiter").asText()),
                // A document that cannot be read still moves, and is only left out of the list.
                () -> assertFalse(Files.exists(damaged)),
                () -> assertEquals(Set.of("csv/e2e-csv-preset.json", "damaged.json"), templateFileNames())
        );
    }

    @Test
    void adoptsEachLegacyDocumentOnlyOnce() throws IOException {
        writeLegacyTemplate("e2e-csv-pipe.json", "e2e-csv-pipe", csvOptions("UTF-8", "|", 1));

        List<String> first = names(service().list());
        List<String> second = names(service().list());

        assertAll(
                () -> assertEquals(List.of("e2e-csv-pipe"), first),
                () -> assertEquals(List.of("e2e-csv-pipe"), second),
                () -> assertEquals(Set.of("csv/e2e-csv-pipe.json"), templateFileNames())
        );
    }

    @Test
    void keepsALegacyDocumentWhoseTypeCannotBeInferredAsUnknown() throws IOException {
        Path unknown = writeLegacyTemplate("mapping-era.json", "mapping-era",
                Map.of("columnMappings", Map.of("id", "user_id")));

        List<ImportTemplate> stored = service().list();

        assertAll(
                () -> assertEquals(List.of("mapping-era"), names(stored)),
                () -> assertNull(stored.get(0).getFileType()),
                () -> assertFalse(Files.exists(unknown), "an unknown type must not drop the document"),
                () -> assertTrue(Files.exists(storageDirectory.resolve("mapping-era.json"))),
                () -> assertEquals(List.of(), names(service().list("CSV"))),
                () -> assertEquals(Set.of("mapping-era.json"), templateFileNames())
        );
    }

    @Test
    void deletesOnlyTheNamedTemplate() {
        service.upsert(template("keep", "CSV", csvOptions("UTF-8", ",", 1)));
        service.upsert(template("drop", "CSV", csvOptions("UTF-8", ",", 1)));

        List<ImportTemplate> remaining = service.delete("drop");

        assertEquals(List.of("keep"), names(remaining));
        assertEquals(Set.of("csv/keep.json"), templateFileNames());
        assertEquals(List.of("keep"), names(service.delete("missing")));
        assertEquals(List.of("keep"), names(service.delete("  ")));
    }

    @Test
    void deletesATemplateTheAuthorMovedIntoAnotherFileTypeDirectory() throws IOException {
        service.upsert(template("moved", "CSV", csvOptions("UTF-8", ",", 1)));
        Files.createDirectories(storageDirectory.resolve("json"));
        Path moved = Files.move(storageDirectory.resolve("csv/moved.json"),
                storageDirectory.resolve("json/moved.json"));

        List<ImportTemplate> remaining = service.delete("moved", "CSV");

        assertAll(
                () -> assertEquals(List.of(), names(remaining)),
                () -> assertFalse(Files.exists(moved)),
                () -> assertEquals(Set.of(), templateFileNames())
        );
    }

    @Test
    void deletesAnUnreadableDocumentByItsSanitizedFileNameInTheNamedTypeDirectory() throws IOException {
        Files.createDirectories(storageDirectory.resolve("csv"));
        Path damaged = Files.writeString(storageDirectory.resolve("csv/damaged-name.json"),
                "{\"version\":1,\"name\":\"damaged-name\"", StandardCharsets.UTF_8);

        assertEquals(List.of(), names(service().list()));
        List<ImportTemplate> remaining = service.delete("damaged-name", "CSV");

        assertAll(
                () -> assertEquals(List.of(), names(remaining)),
                () -> assertFalse(Files.exists(damaged))
        );
    }

    @Test
    void deletesAnUnreadableDocumentByItsSanitizedFileNameWithoutAType() throws IOException {
        Files.createDirectories(storageDirectory.resolve("json"));
        Path damaged = Files.writeString(storageDirectory.resolve("json/broken-name.json"),
                "{\"version\":1,\"name\":\"broken-name\"", StandardCharsets.UTF_8);

        service.delete("broken-name");

        assertFalse(Files.exists(damaged));
    }

    @Test
    void skipsOnlyTheDamagedFileAndKeepsTheOthers() throws IOException {
        service.upsert(template("good", "CSV", csvOptions("UTF-8", ",", 1)));
        Path damaged = storageDirectory.resolve("damaged.json");
        Files.writeString(damaged, "{\"version\":1,\"name\":\"damaged\"", StandardCharsets.UTF_8);
        String damagedContent = Files.readString(damaged, StandardCharsets.UTF_8);

        List<ImportTemplate> stored = service().list();

        assertAll(
                () -> assertEquals(List.of("good"), names(stored)),
                () -> assertEquals(damagedContent, Files.readString(damaged, StandardCharsets.UTF_8)),
                () -> assertTrue(Files.exists(damaged))
        );
    }

    @Test
    void ignoresTemporaryHiddenAndForeignFiles() throws IOException {
        service.upsert(template("visible", "CSV", csvOptions("UTF-8", ",", 1)));
        Files.writeString(storageDirectory.resolve(".hidden.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(storageDirectory.resolve(".import-templates-42.tmp"), "{}", StandardCharsets.UTF_8);
        Files.writeString(storageDirectory.resolve("notes.txt"), "{}", StandardCharsets.UTF_8);
        Files.createDirectories(storageDirectory.resolve("not-a-type-directory"));
        Files.writeString(storageDirectory.resolve("not-a-type-directory/other.json"),
                "{\"version\":1,\"name\":\"other\",\"formatOptions\":{}}", StandardCharsets.UTF_8);

        assertEquals(List.of("visible"), names(service().list()));
    }

    @Test
    void sanitizesTemplateNamesIntoSafeFileNames() {
        service.upsert(template("../escaped", "CSV", csvOptions("UTF-8", ",", 1)));
        service.upsert(template("bench 表/月度", "CSV", csvOptions("UTF-8", ",", 1)));

        Set<String> files = templateFileNames();

        assertAll(
                () -> assertEquals(Set.of("csv/escaped.json", "csv/bench 表-月度.json"), files),
                () -> assertTrue(files.stream().noneMatch(name -> name.contains("..")), () -> "unsafe name: " + files),
                () -> assertFalse(Files.exists(tempDirectory.resolve("escaped.json"))),
                () -> assertEquals(List.of("../escaped", "bench 表/月度"),
                        names(service().list()).stream().sorted().collect(Collectors.toList()))
        );
    }

    @Test
    void numbersFileNamesThatCollideAfterSanitizing() throws IOException {
        service.upsert(template("a/b", "CSV", csvOptions("UTF-8", ",", 1)));
        service.upsert(template("a-b", "CSV", csvOptions("GBK", ";", 2)));

        assertEquals(Set.of("csv/a-b.json", "csv/a-b-2.json"), templateFileNames());
        assertEquals(List.of("a-b", "a/b"), names(service().list()).stream().sorted().collect(Collectors.toList()));
        JsonNode colliding = objectMapper.readTree(storageDirectory.resolve("csv/a-b-2.json").toFile());
        assertAll(
                () -> assertEquals("a-b", colliding.path("name").asText()),
                () -> assertEquals("GBK", colliding.path("formatOptions").path("csvOptions").path("encoding").asText())
        );

        // Saving the first template again keeps its own file; the colliding file is untouched.
        service.upsert(template("a/b", "CSV", csvOptions("GB18030", ",", 1)));

        assertEquals(Set.of("csv/a-b.json", "csv/a-b-2.json"), templateFileNames());
        JsonNode first = objectMapper.readTree(storageDirectory.resolve("csv/a-b.json").toFile());
        assertAll(
                () -> assertEquals("GB18030", first.path("formatOptions").path("csvOptions").path("encoding").asText()),
                () -> assertEquals("GBK", objectMapper.readTree(storageDirectory.resolve("csv/a-b-2.json").toFile())
                        .path("formatOptions").path("csvOptions").path("encoding").asText())
        );
    }

    @Test
    void rejectsNamesThatCarryNothingUsable() {
        BusinessException blank = assertThrows(BusinessException.class,
                () -> service.upsert(template("   ", "CSV", csvOptions("UTF-8", ",", 1))));
        BusinessException unusable = assertThrows(BusinessException.class,
                () -> service.upsert(template("///", "CSV", csvOptions("UTF-8", ",", 1))));

        assertAll(
                () -> assertEquals("import.template.nameRequired", blank.getCode()),
                () -> assertEquals("import.template.nameRequired", unusable.getCode()),
                () -> assertEquals(Set.of(), templateFileNames())
        );
    }

    @Test
    void rejectsOverLongNamesAndMissingFormatOptions() {
        BusinessException tooLong = assertThrows(BusinessException.class, () -> service.upsert(
                template("t".repeat(ImportTemplateServiceImpl.MAX_NAME_LENGTH + 1), "CSV", csvOptions("UTF-8", ",", 1))));
        ImportTemplate withoutOptions = template("no-options", "CSV", csvOptions("UTF-8", ",", 1));
        withoutOptions.setFormatOptions(null);
        BusinessException missingOptions = assertThrows(BusinessException.class,
                () -> service.upsert(withoutOptions));
        ImportTemplate emptyOptions = template("empty-options", "CSV", csvOptions("UTF-8", ",", 1));
        emptyOptions.setFormatOptions(Map.of());
        BusinessException empty = assertThrows(BusinessException.class, () -> service.upsert(emptyOptions));

        assertAll(
                () -> assertEquals("import.template.nameTooLong", tooLong.getCode()),
                () -> assertEquals(ImportTemplateServiceImpl.MAX_NAME_LENGTH, tooLong.getArgs()[0]),
                () -> assertEquals("import.template.formatOptionsRequired", missingOptions.getCode()),
                () -> assertEquals("import.template.formatOptionsRequired", empty.getCode()),
                () -> assertEquals(Set.of(), templateFileNames())
        );
    }

    @Test
    void listsTheNewestTemplateFirstAndThenByName() {
        service.upsert(template("older", 1000L, "CSV", csvOptions("UTF-8", ",", 1)));
        service.upsert(template("newer", 3000L, "CSV", csvOptions("UTF-8", ",", 1)));
        service.upsert(template("same-time-b", 2000L, "JSON", jsonOptions("UTF-8")));
        service.upsert(template("same-time-a", 2000L, "JSON", jsonOptions("UTF-8")));

        assertEquals(List.of("newer", "same-time-a", "same-time-b", "older"), names(service().list()));
        assertEquals(List.of("same-time-a", "same-time-b"), names(service().list("JSON")));
    }

    @Test
    void anEmptyOrMissingDirectoryReadsAsAnEmptyList() {
        assertEquals(List.of(), service().list());
        assertEquals(List.of(), service().list("CSV"));
        assertFalse(Files.exists(storageDirectory));
    }

    @Test
    void concurrentSavesDoNotLoseTemplatesOrReuseAFileName() throws Exception {
        int templateCount = 8;
        CountDownLatch ready = new CountDownLatch(templateCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(templateCount);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int index = 0; index < templateCount; index++) {
                int templateIndex = index;
                writes.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    service.upsert(template("concurrent/" + templateIndex, "CSV", csvOptions("UTF-8", ",", 1)));
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> write : writes) {
                write.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(
                IntStream.range(0, templateCount)
                        .mapToObj(index -> "csv/concurrent-" + index + ".json")
                        .collect(Collectors.toSet()),
                templateFileNames());
        assertEquals(
                IntStream.range(0, templateCount).mapToObj(index -> "concurrent/" + index).collect(Collectors.toSet()),
                names(service().list()).stream().collect(Collectors.toSet()));
    }

    @Test
    void reportsAStoreFailureWithTheStorageErrorCode() {
        service.upsert(template("before", "CSV", csvOptions("UTF-8", ",", 1)));

        ImportTemplateServiceImpl failing = new ImportTemplateServiceImpl(
                new FailingImportTemplateStore(), new ImportTemplateConverter());

        BusinessException failure = assertThrows(BusinessException.class,
                () -> failing.upsert(template("before", "CSV", csvOptions("GBK", ";", 2))));

        assertAll(
                () -> assertEquals("import.template.storageFailed", failure.getCode()),
                () -> assertEquals(1, failure.getArgs().length),
                // The stored template is untouched, and the failure leaves no temporary file behind.
                () -> assertEquals("UTF-8", csvOptionsOf(service().list().get(0)).get("encoding")),
                () -> assertEquals(0, temporaryFileCount())
        );
    }

    @Test
    void writesTheTemplateAsReadableJson() throws IOException {
        service.upsert(template("flush", "CSV", csvOptions("UTF-8", ",", 1)));

        String written = Files.readString(storageDirectory.resolve("csv/flush.json"), StandardCharsets.UTF_8);

        assertAll(
                () -> assertTrue(written.contains("\"name\" : \"flush\""), written),
                () -> assertTrue(written.contains("\"fileType\" : \"CSV\""), written),
                () -> assertTrue(written.contains("\"formatOptions\""), written)
        );
    }

    private ImportTemplateServiceImpl service() {
        return new ImportTemplateServiceImpl(store(), new ImportTemplateConverter());
    }

    private FileImportTemplateStore store() {
        return new FileImportTemplateStore(objectMapper, storageDirectory, legacyDirectory);
    }

    private ImportTemplate template(String name, Map<String, Object> formatOptions) {
        return template(name, CREATED_AT, null, formatOptions);
    }

    private ImportTemplate template(String name, String fileType, Map<String, Object> formatOptions) {
        return template(name, CREATED_AT, fileType, formatOptions);
    }

    private ImportTemplate template(String name, long createdAt, String fileType, Map<String, Object> formatOptions) {
        ImportTemplate template = new ImportTemplate();
        template.setName(name);
        template.setCreatedAt(createdAt);
        template.setFileType(fileType);
        template.setFormatOptions(formatOptions);
        return template;
    }

    /** A document of the version that predates the file type field, in the location it was kept in. */
    private Path writeLegacyTemplate(String fileName, String name, Map<String, Object> formatOptions)
            throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", 1);
        document.put("name", name);
        document.put("createdAt", CREATED_AT);
        document.put("formatOptions", formatOptions);
        Path file = legacyDirectory.resolve(fileName);
        Files.createDirectories(file.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), document);
        return file;
    }

    private Path writeLegacyText(String fileName, String content) throws IOException {
        Path file = legacyDirectory.resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Map<String, Object> csvOptions(String encoding, String delimiter, int headerRow) {
        return Map.of("csvOptions", Map.of(
                "encoding", encoding,
                "delimiter", delimiter,
                "headerRow", headerRow,
                "hasHeader", true));
    }

    private Map<String, Object> jsonOptions(String encoding) {
        return Map.of("jsonOptions", Map.of("encoding", encoding, "structure", "ARRAY"));
    }

    private Map<String, Object> excelOptions() {
        return Map.of("excelOptions", Map.of("sheetIndex", 0, "hasHeader", true));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> csvOptionsOf(ImportTemplate template) {
        return (Map<String, Object>) template.getFormatOptions().get("csvOptions");
    }

    private List<String> names(List<ImportTemplate> templates) {
        return templates.stream().map(ImportTemplate::getName).collect(Collectors.toList());
    }

    private List<String> fileTypesOf(List<ImportTemplate> templates) {
        return templates.stream().map(ImportTemplate::getFileType).collect(Collectors.toList());
    }

    private Set<String> templateFileNames() {
        return new LinkedHashSet<>(templateFiles());
    }

    /** Every file below the storage directory, as a path relative to it. */
    private List<String> templateFiles() {
        if (!Files.isDirectory(storageDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(storageDirectory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> storageDirectory.relativize(file).toString().replace(File.separatorChar, '/'))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Set<String> fieldNames(JsonNode document) {
        Set<String> names = new LinkedHashSet<>();
        document.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private long temporaryFileCount() throws IOException {
        try (Stream<Path> files = Files.walk(storageDirectory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().startsWith(".import-templates-"))
                    .count();
        }
    }

    /** The real store with a write that always fails, to check the reported storage error. */
    private final class FailingImportTemplateStore implements IImportTemplateStore {

        private final FileImportTemplateStore delegate = store();

        @Override
        public List<ImportTemplate> read() {
            return delegate.read();
        }

        @Override
        public List<ImportTemplate> read(String fileType) {
            return delegate.read(fileType);
        }

        @Override
        public void save(ImportTemplate template) {
            throw new UncheckedIOException("forced save failure", new IOException("forced save failure"));
        }

        @Override
        public void delete(String name, String fileType) {
            delegate.delete(name, fileType);
        }

        @Override
        public String sanitizedFileName(String name) {
            return delegate.sanitizedFileName(name);
        }

        @Override
        public int migrateLegacyTemplates() {
            return delegate.migrateLegacyTemplates();
        }
    }
}

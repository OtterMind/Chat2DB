package ai.chat2db.community.domain.core.impl.imports;

import ai.chat2db.community.domain.api.service.imports.ImportTemplateFileTypes;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImportTemplateFileTypesTest {

    @Test
    void normalizesTheFileTypesTemplatesSupport() {
        assertAll(
                () -> assertEquals("CSV", ImportTemplateFileTypes.normalize("CSV")),
                () -> assertEquals("CSV", ImportTemplateFileTypes.normalize(" csv ")),
                () -> assertEquals("XLSX", ImportTemplateFileTypes.normalize("xlsx")),
                () -> assertEquals("XLS", ImportTemplateFileTypes.normalize("Xls")),
                () -> assertEquals("JSON", ImportTemplateFileTypes.normalize("json")),
                () -> assertNull(ImportTemplateFileTypes.normalize(null)),
                () -> assertNull(ImportTemplateFileTypes.normalize("   ")),
                () -> assertNull(ImportTemplateFileTypes.normalize("SQL")),
                () -> assertNull(ImportTemplateFileTypes.normalize("ZIP"))
        );
    }

    @Test
    void infersTheFileTypeOfALegacyDocumentFromItsOptionGroup() {
        assertAll(
                () -> assertEquals("CSV", ImportTemplateFileTypes.infer(Map.of("csvOptions", Map.of("delimiter", ";"))),
                        "legacy csvOptions belong to CSV"),
                () -> assertEquals("JSON", ImportTemplateFileTypes.infer(Map.of("jsonOptions", Map.of("dataPath", "$"))),
                        "legacy jsonOptions belong to JSON"),
                () -> assertEquals("XLSX", ImportTemplateFileTypes.infer(Map.of("excelOptions", Map.of("sheetIndex", 0))),
                        "legacy excelOptions resolve to the modern XLSX"),
                () -> assertNull(ImportTemplateFileTypes.infer(Map.of("columnMappings", Map.of("id", "user_id")))),
                () -> assertNull(ImportTemplateFileTypes.infer(Map.of())),
                () -> assertNull(ImportTemplateFileTypes.infer(null))
        );
    }

    @Test
    void resolvesTheRecordedTypeBeforeTheInferredOne() {
        Map<String, Object> csvOptions = Map.of("csvOptions", Map.of("delimiter", ","));
        Map<String, Object> conflicting = new LinkedHashMap<>();
        conflicting.put("csvOptions", Map.of("delimiter", ";"));
        conflicting.put("jsonOptions", Map.of("dataPath", "$"));

        assertAll(
                // A recorded type wins even when the option group says something else.
                () -> assertEquals("JSON", ImportTemplateFileTypes.resolve("JSON", csvOptions)),
                () -> assertEquals("CSV", ImportTemplateFileTypes.resolve(null, csvOptions)),
                () -> assertEquals("CSV", ImportTemplateFileTypes.resolve("no-such-type", csvOptions)),
                // Several option groups resolve in the documented order instead of failing.
                () -> assertEquals("CSV", ImportTemplateFileTypes.resolve(null, conflicting)),
                () -> assertNull(ImportTemplateFileTypes.resolve(null, Map.of("columnMappings", Map.of())))
        );
    }

    @Test
    void mapsEverySupportedTypeToItsLowerCaseDirectory() {
        assertAll(
                () -> assertEquals("csv", ImportTemplateFileTypes.directoryName("CSV")),
                () -> assertEquals("xlsx", ImportTemplateFileTypes.directoryName("XLSX")),
                () -> assertEquals("xls", ImportTemplateFileTypes.directoryName("XLS")),
                () -> assertEquals("json", ImportTemplateFileTypes.directoryName("JSON")),
                () -> assertNull(ImportTemplateFileTypes.directoryName(null)),
                () -> assertNull(ImportTemplateFileTypes.directoryName("not-a-type")),
                () -> assertEquals(List.of("csv", "xlsx", "xls", "json"), ImportTemplateFileTypes.directoryNames())
        );
    }
}

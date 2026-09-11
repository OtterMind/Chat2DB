package ai.chat2db.community.domain.core.impl.ncx.dbeaver;

import ai.chat2db.community.domain.core.impl.ncx.ExportConstants;
import ai.chat2db.community.domain.core.impl.ncx.XMLUtils;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static ai.chat2db.community.domain.core.impl.ncx.dbeaver.DbeaverArchiveExtraction.MAX_BYTES;
import static ai.chat2db.community.domain.core.impl.ncx.dbeaver.DbeaverArchiveExtraction.MAX_ENTRIES;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the boundaries of one extraction: a name taken from the archive may only address a direct
 * child of the directory it belongs to, and the amount of extracted data is bounded. Both are
 * properties of the extractor itself, so they are checked with limits small enough to keep the test
 * fast instead of relying on a huge fixture.
 */
class DbeaverArchiveExtractionTest {

    private static final String CONFIG_DIR = ExportConstants.CONFIG_FILE;

    @TempDir
    Path tempDir;

    @Test
    void extractsDeclaredResourcesIntoTheExtractionDirectory() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("projects/demo/" + CONFIG_DIR + "/", new byte[0]);
        entries.put("projects/demo/" + CONFIG_DIR + "/data-sources.json", "{}".getBytes(StandardCharsets.UTF_8));
        File archive = archive(entries);

        try (ZipFile zipFile = new ZipFile(archive)) {
            DbeaverArchiveExtraction extraction = DbeaverArchiveExtraction.create(tempDir, zipFile);
            File projectDirectory = extraction.extractProject(project("demo", "data-sources.json"));

            assertTrue(new File(projectDirectory, CONFIG_DIR + "/data-sources.json").isFile());
            assertTrue(projectDirectory.toPath().startsWith(extraction.getExtractionRoot().toPath()));
        }
    }

    @Test
    void rejectsAProjectNameThatEscapesTheExtractionDirectory() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("projects/../escaped/" + CONFIG_DIR + "/pwned.txt", "pwned".getBytes(StandardCharsets.UTF_8));
        File archive = archive(entries);

        try (ZipFile zipFile = new ZipFile(archive)) {
            DbeaverArchiveExtraction extraction = DbeaverArchiveExtraction.create(tempDir, zipFile);

            assertThrows(BusinessException.class, () -> extraction.extractProject(project("../escaped", "pwned.txt")));
            assertFalse(Files.exists(tempDir.resolve("escaped")), "extraction wrote outside the extraction directory");
        }
    }

    @Test
    void rejectsAResourceNameThatEscapesTheExtractionDirectory() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("projects/demo/" + CONFIG_DIR + "/", new byte[0]);
        entries.put("projects/demo/" + CONFIG_DIR + "/../../pwned.txt", "pwned".getBytes(StandardCharsets.UTF_8));
        File archive = archive(entries);

        try (ZipFile zipFile = new ZipFile(archive)) {
            DbeaverArchiveExtraction extraction = DbeaverArchiveExtraction.create(tempDir, zipFile);

            assertThrows(BusinessException.class,
                    () -> extraction.extractProject(project("demo", "../../pwned.txt")));
            assertFalse(Files.exists(tempDir.resolve("pwned.txt")), "extraction wrote outside the extraction directory");
        }
    }

    @Test
    void rejectsAResourceNameThatIsNotASinglePathSegment() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("projects/demo/" + CONFIG_DIR + "/", new byte[0]);
        entries.put("projects/demo/" + CONFIG_DIR + "/etc/pwned.txt", "pwned".getBytes(StandardCharsets.UTF_8));
        File archive = archive(entries);

        try (ZipFile zipFile = new ZipFile(archive)) {
            DbeaverArchiveExtraction extraction = DbeaverArchiveExtraction.create(tempDir, zipFile);

            assertThrows(BusinessException.class, () -> extraction.extractProject(project("demo", "/etc/pwned.txt")));
            assertFalse(Files.exists(tempDir.resolve("etc")), "extraction wrote outside the extraction directory");
        }
    }

    @Test
    void rejectsAnArchiveWithMoreEntriesThanTheEntryBudget() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("projects/demo/" + CONFIG_DIR + "/", new byte[0]);
        entries.put("projects/demo/" + CONFIG_DIR + "/a.json", "{}".getBytes(StandardCharsets.UTF_8));
        entries.put("projects/demo/" + CONFIG_DIR + "/b.json", "{}".getBytes(StandardCharsets.UTF_8));
        File archive = archive(entries);
        Path extractionRoot = Files.createDirectory(tempDir.resolve("entries-budget"));

        try (ZipFile zipFile = new ZipFile(archive)) {
            DbeaverArchiveExtraction bounded = new DbeaverArchiveExtraction(extractionRoot, zipFile, 2, MAX_BYTES);

            assertThrows(BusinessException.class,
                    () -> bounded.extractProject(project("demo", "a.json", "b.json")));
        }
    }

    @Test
    void rejectsAnArchiveThatExceedsTheByteBudget() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] payload = new byte[64];
        entries.put("projects/demo/" + CONFIG_DIR + "/", new byte[0]);
        entries.put("projects/demo/" + CONFIG_DIR + "/big.json", payload);
        File archive = archive(entries);
        Path extractionRoot = Files.createDirectory(tempDir.resolve("byte-budget"));

        try (ZipFile zipFile = new ZipFile(archive)) {
            DbeaverArchiveExtraction bounded = new DbeaverArchiveExtraction(extractionRoot, zipFile, MAX_ENTRIES, 8);

            assertThrows(BusinessException.class, () -> bounded.extractProject(project("demo", "big.json")));
        }
    }

    /**
     * Parses a one-project {@code meta.xml} whose {@code .dbeaver} resource declares the given files.
     */
    private static Element project(String projectName, String... resourceNames) throws Exception {
        StringBuilder meta = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<archive version=\"1.0\"><projects><project name=\"").append(projectName).append("\">")
                .append("<resource name=\"").append(CONFIG_DIR).append("\">");
        for (String resourceName : resourceNames) {
            meta.append("<resource name=\"").append(resourceName).append("\"/>");
        }
        meta.append("</resource></project></projects></archive>");

        Document document = XMLUtils.parseDocument(
                new ByteArrayInputStream(meta.toString().getBytes(StandardCharsets.UTF_8)));
        Element projects = XMLUtils.getChildElement(document.getDocumentElement(), ExportConstants.TAG_PROJECTS);
        return XMLUtils.getChildElement(projects, ExportConstants.TAG_PROJECT);
    }

    private File archive(Map<String, byte[]> entries) throws IOException {
        File archive = tempDir.resolve("archive-" + UUID.randomUUID() + ".dbp").toFile();
        try (OutputStream out = Files.newOutputStream(archive.toPath());
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return archive;
    }
}

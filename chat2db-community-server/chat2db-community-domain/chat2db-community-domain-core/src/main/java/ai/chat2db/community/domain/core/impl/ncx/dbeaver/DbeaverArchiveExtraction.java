package ai.chat2db.community.domain.core.impl.ncx.dbeaver;

import ai.chat2db.community.domain.core.impl.ncx.ExportConstants;
import ai.chat2db.community.domain.core.impl.ncx.XMLUtils;
import ai.chat2db.community.tools.exception.BusinessException;
import cn.hutool.core.io.FileUtil;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Element;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts the projects of one DBeaver archive into a directory that belongs to that import alone.
 * <p>
 * Everything the extraction writes is named by the archive itself: the project and resource names
 * come from the archive's {@code meta.xml}, and the {@code .dbp} only has to carry entries whose
 * names match them. A name is therefore treated as untrusted input - it must be a single path
 * segment and the resolved target must stay directly inside the directory it was resolved against -
 * so a crafted archive cannot write outside the extraction directory, and the caller never has to
 * delete a path it did not create.
 * <p>
 * Extraction is bounded by an entry count and a byte budget, because a small archive can declare
 * entries that expand to an arbitrarily large payload. The budget is spent by the bytes actually
 * copied, not by the size the archive declares.
 * <p>
 * An instance handles one import and is not thread-safe.
 */
public class DbeaverArchiveExtraction {

    /**
     * Prefix of the private directory created for one import under the Chat2DB base path.
     */
    public static final String EXTRACTION_DIRECTORY_PREFIX = "dbp-import-";

    /**
     * Upper bound on the number of archive entries one import may extract.
     */
    public static final int MAX_ENTRIES = 10_000;

    /**
     * Upper bound on the number of bytes one import may extract.
     */
    public static final long MAX_BYTES = 256L * 1024 * 1024;

    private static final String UNSAFE_NAME_CODE = "connection.import.dbeaver.unsafeArchive";
    private static final String TOO_LARGE_CODE = "connection.import.dbeaver.archiveTooLarge";
    private static final int COPY_BUFFER_SIZE = 8192;

    private final Path extractionRoot;
    private final ZipFile zipFile;
    private final int maxEntries;
    private final long maxBytes;

    private int remainingEntries;
    private long remainingBytes;

    DbeaverArchiveExtraction(Path extractionRoot, ZipFile zipFile, int maxEntries, long maxBytes) {
        this.extractionRoot = extractionRoot.normalize().toAbsolutePath();
        this.zipFile = zipFile;
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.remainingEntries = maxEntries;
        this.remainingBytes = maxBytes;
    }

    /**
     * Creates the private directory of one import below the given base path.
     *
     * @param basePath directory the import is allowed to create its working directory in.
     * @param zipFile  archive being imported.
     * @return extraction bound to a directory that did not exist before this call.
     */
    public static DbeaverArchiveExtraction create(Path basePath, ZipFile zipFile) throws IOException {
        return new DbeaverArchiveExtraction(
                Files.createTempDirectory(basePath, EXTRACTION_DIRECTORY_PREFIX), zipFile, MAX_ENTRIES, MAX_BYTES);
    }

    /**
     * @return directory owned by this extraction. It is the only path the caller may delete.
     */
    public File getExtractionRoot() {
        return extractionRoot.toFile();
    }

    /**
     * Extracts the resources of one {@code <project>} element of {@code meta.xml}.
     *
     * @param projectElement project element of the parsed meta document.
     * @return the project directory, which holds the archive's {@code .dbeaver} directory.
     * @throws BusinessException when the project name, a resource name, or the archive size is not
     *                           acceptable.
     */
    public File extractProject(Element projectElement) throws IOException {
        String projectName = requireSafeName(projectElement.getAttribute(ExportConstants.ATTR_NAME));
        Path projectDirectory = resolveWithin(extractionRoot, projectName);
        File configDirectory = resolveWithin(projectDirectory, ExportConstants.CONFIG_FILE).toFile();
        extractResources(configDirectory, projectElement, ExportConstants.DIR_PROJECTS + "/" + projectName + "/");
        return projectDirectory.toFile();
    }

    /**
     * Walks the resource tree of one element and copies the archive entries it declares. The
     * recursion mirrors the archive layout: a resource element that resolves to a directory entry
     * keeps the directory it was declared in and collects its children next to it.
     */
    private void extractResources(File resourceDirectory, Element resourceElement, String containerPath)
            throws IOException {
        for (Element childElement : XMLUtils.getChildElementList(resourceElement, ExportConstants.TAG_RESOURCE)) {
            String childName = requireSafeName(childElement.getAttribute(ExportConstants.ATTR_NAME));
            String entryPath = containerPath + childName;
            ZipEntry resourceEntry = zipFile.getEntry(entryPath);
            if (resourceEntry == null) {
                continue;
            }
            claimEntry(resourceEntry);
            if (resourceEntry.isDirectory()) {
                if (!resourceDirectory.exists()) {
                    FileUtil.mkdir(resourceDirectory);
                }
                extractResources(resourceDirectory, childElement, entryPath + "/");
            } else {
                copyEntry(resourceEntry, resolveWithin(resourceDirectory.toPath(), childName).toFile());
            }
        }
    }

    /**
     * Copies one entry, charging every byte to the budget before it reaches the disk.
     */
    private void copyEntry(ZipEntry entry, File target) throws IOException {
        File parent = target.getParentFile();
        if (null != parent && !parent.exists()) {
            FileUtil.mkdir(parent);
        }
        try (InputStream source = zipFile.getInputStream(entry);
             OutputStream destination = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read = source.read(buffer);
            while (read != -1) {
                remainingBytes -= read;
                if (remainingBytes < 0) {
                    throw tooLarge(entry);
                }
                destination.write(buffer, 0, read);
                read = source.read(buffer);
            }
        }
    }

    /**
     * Charges one entry against the entry count and rejects a declared size that already exceeds the
     * remaining budget. A declared size is a cheap early rejection only: it is chosen by the archive,
     * so {@link #copyEntry} still counts the bytes it really reads.
     */
    private void claimEntry(ZipEntry entry) {
        remainingEntries--;
        if (remainingEntries < 0) {
            throw tooLarge(entry);
        }
        if (entry.getSize() > remainingBytes) {
            throw tooLarge(entry);
        }
    }

    /**
     * Rejects a name that could address anything but a direct child of the directory it belongs to.
     */
    private static String requireSafeName(String name) {
        if (StringUtils.isEmpty(name) || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw unsafeName(name);
        }
        return name;
    }

    /**
     * Resolves a single-segment name below a directory and verifies the result stays there. This is
     * the check that holds even if a name reaches this class without going through
     * {@link #requireSafeName}.
     */
    private static Path resolveWithin(Path directory, String name) {
        Path normalizedDirectory = directory.normalize().toAbsolutePath();
        Path resolved;
        try {
            resolved = normalizedDirectory.resolve(name).normalize();
        } catch (InvalidPathException e) {
            throw unsafeName(name);
        }
        if (!normalizedDirectory.equals(resolved.getParent())) {
            throw unsafeName(name);
        }
        return resolved;
    }

    private static BusinessException unsafeName(String name) {
        return new BusinessException(UNSAFE_NAME_CODE, new Object[]{name});
    }

    private static BusinessException tooLarge(ZipEntry entry) {
        return new BusinessException(TOO_LARGE_CODE, new Object[]{entry.getName()});
    }
}

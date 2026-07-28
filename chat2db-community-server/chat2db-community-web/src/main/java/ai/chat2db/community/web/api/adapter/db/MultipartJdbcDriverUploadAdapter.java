package ai.chat2db.community.web.api.adapter.db;

import ai.chat2db.community.domain.api.service.db.IDbJdbcDriverUploadService;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import ai.chat2db.community.tools.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Multipart upload endpoint for JDBC driver jars.
 *
 * <p><b>Security context:</b> prior to this fix, this adapter accepted any
 * {@link MultipartFile} and dropped it into {@code jdbc-lib/} with no
 * validation. Combined with the absence of a class-name whitelist in
 * {@code JdbcDriverManager}, this yielded a two-stage unauthenticated RCE
 * (Stage 1 here, Stage 2 in {@code JdbcDriverManager.getJDBCDriver}). The
 * checks below are defence-in-depth: even if the Layer-2 class whitelist is
 * ever bypassed, an attacker should not be able to drop arbitrary content
 * into the driver directory.</p>
 */
@Slf4j
@Component
public class MultipartJdbcDriverUploadAdapter implements IDbJdbcDriverUploadService<MultipartFile[]> {

    /** Max size of a single uploaded driver jar. 50 MB is generous — MySQL/PG/Oracle drivers are 1–10 MB. */
    private static final long MAX_DRIVER_JAR_BYTES = 50L * 1024 * 1024;

    /** Only simple filenames: alphanumerics, dash, underscore, dot; must end in .jar */
    private static final String SAFE_FILENAME_REGEX = "[a-zA-Z0-9_\\-]+\\.jar";

    /** ZIP local-file-header magic. All JARs are ZIPs. */
    private static final byte[] ZIP_MAGIC = new byte[]{0x50, 0x4B, 0x03, 0x04};

    @Override
    public List<String> upload(MultipartFile[] files) throws IOException {
        List<String> uploadedFiles = new ArrayList<>();
        if (files == null || files.length == 0) {
            return uploadedFiles;
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String safeName = validate(file);
            File target = new File(JdbcDriverConstants.DRIVER_LIB_PATH, safeName);
            // Final containment check — canonical path must stay inside DRIVER_LIB_PATH.
            String canonicalBase = new File(JdbcDriverConstants.DRIVER_LIB_PATH).getCanonicalPath();
            String canonicalTarget = target.getCanonicalPath();
            if (!canonicalTarget.startsWith(canonicalBase + File.separator)) {
                log.warn("SECURITY: driver upload path traversal attempt, name={}", safeName);
                throw new BusinessException("jdbc.driver.invalidPath", new Object[]{safeName});
            }
            file.transferTo(target);
            uploadedFiles.add(safeName);
            log.info("JDBC driver uploaded: name={}, size={} bytes", safeName, file.getSize());
        }
        return uploadedFiles;
    }

    /**
     * Runs all upload validation and returns the sanitized filename. Throws
     * {@link BusinessException} on any rule failure. Nothing touches the
     * filesystem before this method returns successfully.
     */
    private String validate(MultipartFile file) throws IOException {
        // 1. Size cap
        if (file.getSize() > MAX_DRIVER_JAR_BYTES) {
            throw new BusinessException("jdbc.driver.uploadTooLarge",
                    new Object[]{file.getSize(), MAX_DRIVER_JAR_BYTES});
        }

        // 2. Filename validation — strip any client-supplied path, then apply
        //    a strict whitelist so weird names cannot cause trouble downstream.
        String original = file.getOriginalFilename();
        String name = original == null ? null : FilenameUtils.getName(original);
        if (StringUtils.isBlank(name) || !name.matches(SAFE_FILENAME_REGEX)) {
            log.warn("SECURITY: rejected driver upload with invalid filename: {}", original);
            throw new BusinessException("jdbc.driver.invalidFileName", new Object[]{original});
        }

        // 3. Magic bytes — must be a real ZIP (and therefore a real JAR).
        try (InputStream in = file.getInputStream()) {
            byte[] head = new byte[ZIP_MAGIC.length];
            int read = in.read(head);
            if (read < ZIP_MAGIC.length) {
                throw new BusinessException("jdbc.driver.notAJar", new Object[]{name});
            }
            for (int i = 0; i < ZIP_MAGIC.length; i++) {
                if (head[i] != ZIP_MAGIC[i]) {
                    throw new BusinessException("jdbc.driver.notAJar", new Object[]{name});
                }
            }
        }

        // 4. Jar-entry scan — reject zip-slip and require at least one .class
        //    file (a jar with no class files is not a JDBC driver).
        boolean hasClassFile = false;
        try (JarInputStream jar = new JarInputStream(file.getInputStream())) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                String entryName = entry.getName();
                if (entryName.contains("..")) {
                    log.warn("SECURITY: rejected driver jar with zip-slip entry: {} in {}",
                            entryName, name);
                    throw new BusinessException("jdbc.driver.zipSlip", new Object[]{name});
                }
                if (!entry.isDirectory() && entryName.toLowerCase(Locale.ROOT).endsWith(".class")) {
                    hasClassFile = true;
                }
            }
        }
        if (!hasClassFile) {
            throw new BusinessException("jdbc.driver.noClassFiles", new Object[]{name});
        }

        return name;
    }
}

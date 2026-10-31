package ai.chat2db.community.domain.core.impl.imports;

import ai.chat2db.community.domain.api.model.imports.ImportTemplate;
import ai.chat2db.community.domain.api.service.imports.IImportTemplateService;
import ai.chat2db.community.domain.api.service.imports.IImportTemplateStore;
import ai.chat2db.community.domain.api.service.imports.ImportTemplateFileTypes;
import ai.chat2db.community.domain.core.converter.ImportTemplateConverter;
import ai.chat2db.community.tools.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Global import format presets, kept by {@link IImportTemplateStore} as one JSON file per preset
 * under the workspace storage directory, so a template survives a browser change, a port change, and
 * a backend restart.
 *
 * The service owns the contract of the presets: a name is required, bounded, and has to leave a
 * usable file name; the option group is required; a preset belongs to exactly one file format, which
 * the document itself decides; and the list is ordered newest first, then by name. Where a template
 * is filed and how its file is written belongs to the store.
 *
 * The migration of the documents written before the workspace storage directory carried them runs
 * once per process - at startup and on the first read that finds it still pending. A failing
 * migration is only logged: it never fails a read and it never fails the application startup.
 *
 * Every access takes {@link #lock}: a save is a read-modify-write of the template directory and two
 * concurrent saves must not pick the same free file name.
 */
@Service
@Slf4j
public class ImportTemplateServiceImpl implements IImportTemplateService {

    static final int STORAGE_VERSION = 1;

    /** Mirrors the length of the wizard's template name field. */
    static final int MAX_NAME_LENGTH = 80;

    private static final String ERROR_NAME_REQUIRED = "import.template.nameRequired";
    private static final String ERROR_NAME_TOO_LONG = "import.template.nameTooLong";
    private static final String ERROR_FORMAT_OPTIONS_REQUIRED = "import.template.formatOptionsRequired";
    private static final String ERROR_STORAGE_FAILED = "import.template.storageFailed";

    private final IImportTemplateStore importTemplateStore;

    private final ImportTemplateConverter importTemplateConverter;

    private final ReentrantLock lock = new ReentrantLock();

    /** The legacy migration runs at most once per instance, whatever the entry point. */
    private final AtomicBoolean legacyMigrationDone = new AtomicBoolean();

    @Autowired
    public ImportTemplateServiceImpl(IImportTemplateStore importTemplateStore,
            ImportTemplateConverter importTemplateConverter) {
        this.importTemplateStore = importTemplateStore;
        this.importTemplateConverter = importTemplateConverter;
    }

    /** Templates written to the previous location are moved home as soon as the application starts. */
    @PostConstruct
    void migrateLegacyTemplatesOnStartup() {
        migrateLegacyTemplatesOnce();
    }

    @Override
    public List<ImportTemplate> list() {
        return list(null);
    }

    @Override
    public List<ImportTemplate> list(String fileType) {
        lock.lock();
        try {
            migrateLegacyTemplatesOnce();
            return storedTemplates(fileType);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ImportTemplate> upsert(ImportTemplate importTemplate) {
        String name = validatedName(importTemplate);
        lock.lock();
        try {
            migrateLegacyTemplatesOnce();
            ImportTemplate template = storableTemplate(importTemplate, name);
            // The store decides the file name, so a name it cannot store is rejected here.
            if (importTemplateStore.sanitizedFileName(name) == null) {
                throw new BusinessException(ERROR_NAME_REQUIRED);
            }
            try {
                importTemplateStore.save(template);
            } catch (UncheckedIOException e) {
                throw storageFailure(e);
            }
            return storedTemplates(null);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ImportTemplate> delete(String name) {
        return delete(name, null);
    }

    @Override
    public List<ImportTemplate> delete(String name, String fileType) {
        lock.lock();
        try {
            migrateLegacyTemplatesOnce();
            if (StringUtils.isNotBlank(name)) {
                try {
                    importTemplateStore.delete(name, fileType);
                } catch (UncheckedIOException e) {
                    throw storageFailure(e);
                }
            }
            return storedTemplates(null);
        } finally {
            lock.unlock();
        }
    }

    private void migrateLegacyTemplatesOnce() {
        if (!legacyMigrationDone.compareAndSet(false, true)) {
            return;
        }
        try {
            int moved = importTemplateStore.migrateLegacyTemplates();
            if (moved > 0) {
                log.info("Adopted {} legacy import template(s) into the workspace storage directory", moved);
            }
        } catch (RuntimeException e) {
            log.warn("Skipping the legacy import template migration", e);
        }
    }

    /** Every stored preset of the requested file type, newest first and then by name. */
    private List<ImportTemplate> storedTemplates(String fileType) {
        List<ImportTemplate> templates = new ArrayList<>();
        try {
            for (ImportTemplate template : importTemplateStore.read(fileType)) {
                templates.add(readableTemplate(template));
            }
        } catch (UncheckedIOException e) {
            throw storageFailure(e);
        }
        templates.sort(Comparator
                .comparing(ImportTemplate::getCreatedAt)
                .reversed()
                .thenComparing(ImportTemplate::getName));
        return templates;
    }

    /**
     * The preset as the rest of the application reads it: a document written by an older version is
     * completed, and a document that records no usable file type reports the one its option group
     * implies instead of losing its preset.
     */
    private ImportTemplate readableTemplate(ImportTemplate template) {
        ImportTemplate readable = importTemplateConverter.toStoredTemplate(template, template.getName());
        readable.setVersion(template.getVersion() == null ? STORAGE_VERSION : template.getVersion());
        readable.setCreatedAt(template.getCreatedAt() == null ? 0L : template.getCreatedAt());
        readable.setFileType(ImportTemplateFileTypes.resolve(template.getFileType(), template.getFormatOptions()));
        return readable;
    }

    private String validatedName(ImportTemplate importTemplate) {
        String name = importTemplate == null || importTemplate.getName() == null
                ? null : importTemplate.getName().trim();
        if (StringUtils.isBlank(name)) {
            throw new BusinessException(ERROR_NAME_REQUIRED);
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ERROR_NAME_TOO_LONG, new Object[]{MAX_NAME_LENGTH});
        }
        return name;
    }

    private ImportTemplate storableTemplate(ImportTemplate importTemplate, String name) {
        Map<String, Object> formatOptions = importTemplate.getFormatOptions();
        if (formatOptions == null || formatOptions.isEmpty()) {
            throw new BusinessException(ERROR_FORMAT_OPTIONS_REQUIRED);
        }
        ImportTemplate stored = importTemplateConverter.toStoredTemplate(importTemplate, name);
        stored.setVersion(STORAGE_VERSION);
        stored.setCreatedAt(importTemplate.getCreatedAt() == null
                ? System.currentTimeMillis() : importTemplate.getCreatedAt());
        // The caller names the file type; a caller that omits it still gets the type its option
        // group implies, and only a template with neither stays in the root as an unknown type.
        stored.setFileType(ImportTemplateFileTypes.resolve(importTemplate.getFileType(), formatOptions));
        return stored;
    }

    /** A store that cannot read or write a document fails the call with the storage error code. */
    private BusinessException storageFailure(UncheckedIOException failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return new BusinessException(ERROR_STORAGE_FAILED, new Object[]{cause.getMessage()}, cause);
    }
}

package ai.chat2db.community.domain.core.converter;

import ai.chat2db.community.domain.api.model.imports.ImportTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Copies import templates into the exact shape the template files own. */
@Component
public class ImportTemplateConverter {

    /** Maps a template onto its stored form, dropping nothing but normalizing the name. */
    public ImportTemplate toStoredTemplate(ImportTemplate template, String name) {
        if (template == null) {
            return null;
        }
        ImportTemplate stored = new ImportTemplate();
        stored.setVersion(template.getVersion());
        stored.setName(name);
        stored.setCreatedAt(template.getCreatedAt());
        stored.setFileType(template.getFileType());
        stored.setFormatOptions(toStoredFormatOptions(template.getFormatOptions()));
        return stored;
    }

    private Map<String, Object> toStoredFormatOptions(Map<String, Object> formatOptions) {
        return formatOptions == null ? null : new LinkedHashMap<>(formatOptions);
    }
}

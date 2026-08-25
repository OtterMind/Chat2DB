package ai.chat2db.community.domain.core.impl.datawiki;

import ai.chat2db.community.domain.api.model.datawiki.DataWikiColumn;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDefinition;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocument;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocumentBundle;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiResource;
import ai.chat2db.community.domain.api.model.request.datawiki.DataWikiCreateRequest;
import ai.chat2db.community.domain.api.model.request.datawiki.DataWikiUpdateRequest;
import ai.chat2db.community.domain.api.service.datawiki.IDataWikiService;
import ai.chat2db.community.domain.api.service.storage.IDataWikiStorage;
import ai.chat2db.community.domain.api.service.storage.IDataWikiDocumentStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class DataWikiServiceImpl implements IDataWikiService {

    private final IDataWikiStorage storage;
    private final IDataWikiDocumentStorage documentStorage;

    public DataWikiServiceImpl(IDataWikiStorage storage, IDataWikiDocumentStorage documentStorage) {
        this.storage = storage;
        this.documentStorage = documentStorage;
    }

    @Override
    public DataWikiDefinition create(DataWikiCreateRequest request) {
        if (request == null || StringUtils.isBlank(request.getName())) {
            throw new IllegalArgumentException("DataWiki name is required");
        }
        Date now = new Date();
        DataWikiDefinition dataWiki = new DataWikiDefinition();
        dataWiki.setId(UUID.randomUUID().toString());
        dataWiki.setName(normalizeName(request.getName()));
        dataWiki.setDescription(StringUtils.trimToNull(request.getDescription()));
        dataWiki.setCreatedBy(request.getCreatedBy());
        dataWiki.setGmtCreate(now);
        dataWiki.setGmtModified(now);
        dataWiki.setRevision(1L);
        DataWikiDefinition created = storage.create(dataWiki);
        synchronize(created);
        return created;
    }

    @Override
    public DataWikiDefinition get(String id) {
        if (StringUtils.isBlank(id)) throw new IllegalArgumentException("DataWiki id is required");
        DataWikiDefinition dataWiki = storage.get(id);
        if (dataWiki == null) throw new NoSuchElementException("DataWiki not found: " + id);
        return dataWiki;
    }

    @Override
    public List<DataWikiDefinition> list() {
        return storage.list();
    }

    @Override
    public DataWikiDefinition update(DataWikiUpdateRequest request) {
        if (request == null || StringUtils.isBlank(request.getId()) || request.getExpectedRevision() == null) {
            throw new IllegalArgumentException("DataWiki id and expected revision are required");
        }
        DataWikiDefinition current = get(request.getId());
        current.setName(normalizeName(request.getName()));
        current.setDescription(StringUtils.trimToNull(request.getDescription()));
        current.setResources(normalizeResources(request.getResources()));
        current.setGmtModified(new Date());
        current.setRevision(current.getRevision() + 1);
        DataWikiDefinition updated = storage.update(current, request.getExpectedRevision());
        synchronize(updated);
        return updated;
    }

    @Override
    public void delete(String id, long expectedRevision) {
        get(id);
        storage.delete(id, expectedRevision);
        documentStorage.delete(id);
    }

    @Override
    public String renderMarkdown(String id) {
        return readDocument(id, "README.md");
    }

    @Override
    public DataWikiDocumentBundle documents(String id) {
        DataWikiDefinition wiki = get(id);
        DataWikiDocumentBundle bundle = documentStorage.load(id, wiki.getRevision());
        if (bundle != null) return bundle;
        synchronize(wiki);
        bundle = documentStorage.load(id, wiki.getRevision());
        if (bundle == null) throw new IllegalStateException("DataWiki documents could not be loaded: " + id);
        return bundle;
    }

    @Override
    public String readDocument(String id, String path) {
        documents(id);
        return documentStorage.read(id, path);
    }

    private DataWikiDocumentBundle synchronize(DataWikiDefinition wiki) {
        List<DataWikiDocument> documents = buildDocuments(wiki);
        DataWikiDocumentBundle bundle = new DataWikiDocumentBundle();
        bundle.setDataWikiId(wiki.getId());
        bundle.setRevision(wiki.getRevision());
        bundle.setDocuments(documents);
        bundle.setRootDirectory(documentStorage.synchronize(wiki.getId(), wiki.getRevision(), documents));
        return bundle;
    }

    private List<DataWikiDocument> buildDocuments(DataWikiDefinition wiki) {
        List<DataWikiDocument> tableDocuments = new ArrayList<>();
        Set<String> usedPaths = new HashSet<>();
        for (DataWikiResource resource : wiki.getResources()) {
            String path = tableDocumentPath(resource);
            if (!usedPaths.add(path)) {
                path = path.substring(0, path.length() - 3) + "-" + safeSegment(resource.getId()) + ".md";
                usedPaths.add(path);
            }
            tableDocuments.add(document(path,
                    firstNonBlank(resource.getBusinessName(), qualifiedName(resource)),
                    "TABLE",
                    renderTableMarkdown(resource)));
        }
        List<DataWikiDocument> documents = new ArrayList<>();
        documents.add(document("README.md", wiki.getName(), "README", renderReadme(wiki, tableDocuments)));
        documents.addAll(tableDocuments);
        return documents;
    }

    private String renderReadme(DataWikiDefinition wiki, List<DataWikiDocument> tableDocuments) {
        StringBuilder markdown = new StringBuilder("# ").append(wiki.getName()).append("\n\n");
        if (StringUtils.isNotBlank(wiki.getDescription())) markdown.append(wiki.getDescription()).append("\n\n");
        markdown.append("> Generated by Chat2DB DataWiki revision ").append(wiki.getRevision())
                .append(". The structured DataWiki is the source of truth.\n\n")
                .append("## How agents should use this wiki\n\n")
                .append("- Read this index first, then open only the table documents needed for the task.\n")
                .append("- Treat database, schema, table, and column names as physical identifiers; use business descriptions for intent.\n")
                .append("- Do not infer access to data that is not listed here. Actual access remains constrained by the bound Agent permissions and data scope.\n")
                .append("- Metadata is a saved snapshot. Verify current metadata before relying on structural changes made after this revision.\n")
                .append("- SQL execution and approval continue to follow the bound Agent policy; this wiki does not grant execution permission.\n\n")
                .append("## Table index\n\n");
        if (wiki.getResources().isEmpty()) {
            return markdown.append("_No database resources bound._\n").toString();
        }
        markdown.append("| Table | Business purpose | Document |\n")
                .append("| --- | --- | --- |\n");
        for (int index = 0; index < wiki.getResources().size(); index++) {
            DataWikiResource resource = wiki.getResources().get(index);
            DataWikiDocument document = tableDocuments.get(index);
            markdown.append("| ").append(cell(qualifiedName(resource))).append(" | ")
                    .append(cell(firstNonBlank(resource.getBusinessDescription(), resource.getSourceComment())))
                    .append(" | [Open](").append(document.getPath()).append(") |\n");
        }
        return markdown.toString();
    }

    private String renderTableMarkdown(DataWikiResource resource) {
        String title = firstNonBlank(resource.getBusinessName(), qualifiedName(resource));
        StringBuilder markdown = new StringBuilder("# ").append(title).append("\n\n")
                .append("`" ).append(qualifiedName(resource)).append("`\n\n");
        appendLabel(markdown, "Data source", resource.getDataSourceName());
        appendLabel(markdown, "Database", resource.getDatabaseName());
        appendLabel(markdown, "Schema", resource.getSchemaName());
        appendLabel(markdown, "Table", resource.getTableName());
        appendLabel(markdown, "Business name", resource.getBusinessName());
        appendLabel(markdown, "Description", firstNonBlank(resource.getBusinessDescription(), resource.getSourceComment()));
        markdown.append("\n## Columns\n\n")
                .append("| Column | Type | Business name | Business meaning | Source comment | Samples | Enum description |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (DataWikiColumn column : resource.getColumns()) {
            markdown.append("| ").append(cell(column.getName())).append(" | ")
                    .append(cell(column.getDataType())).append(" | ")
                    .append(cell(column.getBusinessName())).append(" | ")
                    .append(cell(column.getBusinessDescription())).append(" | ")
                    .append(cell(column.getSourceComment())).append(" | ")
                    .append(cell(column.getSampleValues())).append(" | ")
                    .append(cell(column.getEnumDescription())).append(" |\n");
        }
        return markdown.toString();
    }

    private static DataWikiDocument document(String path, String title, String kind, String content) {
        DataWikiDocument document = new DataWikiDocument();
        document.setPath(path);
        document.setTitle(title);
        document.setKind(kind);
        document.setContent(content);
        return document;
    }

    private static String tableDocumentPath(DataWikiResource resource) {
        List<String> segments = new ArrayList<>();
        segments.add("tables");
        segments.add(safeSegment(firstNonBlank(resource.getDataSourceName(), "datasource-" + resource.getDataSourceId())));
        segments.add(safeSegment(firstNonBlank(resource.getDatabaseName(), "_default")));
        if (StringUtils.isNotBlank(resource.getSchemaName())) segments.add(safeSegment(resource.getSchemaName()));
        segments.add(safeSegment(resource.getTableName()) + ".md");
        return String.join("/", segments);
    }

    private static String safeSegment(String value) {
        String source = StringUtils.defaultIfBlank(value, "unnamed").trim();
        String safe = source.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}\\s]+", "-")
                .replaceAll("^\\.+|\\.+$", "")
                .replaceAll("-+", "-");
        safe = StringUtils.defaultIfBlank(safe, "unnamed");
        boolean reserved = safe.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]");
        if (reserved) safe = "_" + safe;
        if (!safe.equals(source) || safe.length() > 80) {
            String prefix = safe.substring(0, Math.min(safe.length(), 64));
            safe = prefix + "-" + shortHash(source);
        }
        return safe;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeName(String name) {
        if (StringUtils.isBlank(name)) throw new IllegalArgumentException("DataWiki name is required");
        String result = name.trim();
        if (result.length() > 128) throw new IllegalArgumentException("DataWiki name must not exceed 128 characters");
        return result;
    }

    private List<DataWikiResource> normalizeResources(List<DataWikiResource> resources) {
        List<DataWikiResource> result = new ArrayList<>();
        for (DataWikiResource resource : resources == null ? List.<DataWikiResource>of() : resources) {
            if (resource == null || resource.getDataSourceId() == null || StringUtils.isBlank(resource.getTableName())) {
                throw new IllegalArgumentException("DataWiki resource datasource and table are required");
            }
            if (StringUtils.isBlank(resource.getId())) resource.setId(UUID.randomUUID().toString());
            if (resource.getColumns() == null) resource.setColumns(new ArrayList<>());
            result.add(resource);
        }
        return result;
    }

    private static String qualifiedName(DataWikiResource resource) {
        return java.util.stream.Stream.of(resource.getDataSourceName(), resource.getDatabaseName(),
                        resource.getSchemaName(), resource.getTableName())
                .filter(StringUtils::isNotBlank).reduce((left, right) -> left + "." + right)
                .orElse(resource.getTableName());
    }

    private static void appendLabel(StringBuilder markdown, String label, String value) {
        if (StringUtils.isNotBlank(value)) markdown.append("**").append(label).append(":** ").append(value.trim()).append("  \n");
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : StringUtils.defaultString(second);
    }

    private static String cell(String value) {
        return StringUtils.defaultString(value, "-").replace("|", "\\|").replace("\n", " ");
    }
}

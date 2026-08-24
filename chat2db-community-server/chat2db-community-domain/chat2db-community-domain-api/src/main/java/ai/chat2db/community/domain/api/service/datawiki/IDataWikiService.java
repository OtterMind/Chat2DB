package ai.chat2db.community.domain.api.service.datawiki;

import ai.chat2db.community.domain.api.model.datawiki.DataWikiDefinition;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocumentBundle;
import ai.chat2db.community.domain.api.model.request.datawiki.DataWikiCreateRequest;
import ai.chat2db.community.domain.api.model.request.datawiki.DataWikiUpdateRequest;

import java.util.List;

public interface IDataWikiService {

    DataWikiDefinition create(DataWikiCreateRequest request);

    DataWikiDefinition get(String id);

    List<DataWikiDefinition> list();

    DataWikiDefinition update(DataWikiUpdateRequest request);

    void delete(String id, long expectedRevision);

    String renderMarkdown(String id);

    DataWikiDocumentBundle documents(String id);

    String readDocument(String id, String path);
}

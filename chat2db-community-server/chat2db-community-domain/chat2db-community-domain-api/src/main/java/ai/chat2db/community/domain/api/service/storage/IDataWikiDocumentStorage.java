package ai.chat2db.community.domain.api.service.storage;

import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocument;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocumentBundle;

import java.util.List;

public interface IDataWikiDocumentStorage {

    String synchronize(String dataWikiId, long revision, List<DataWikiDocument> documents);

    DataWikiDocumentBundle load(String dataWikiId, long expectedRevision);

    String read(String dataWikiId, String documentPath);

    void delete(String dataWikiId);
}

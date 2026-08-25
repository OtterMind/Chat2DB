package ai.chat2db.community.domain.api.service.storage;

import ai.chat2db.community.domain.api.model.datawiki.DataWikiDefinition;

import java.util.List;

public interface IDataWikiStorage {

    DataWikiDefinition create(DataWikiDefinition dataWiki);

    DataWikiDefinition get(String id);

    List<DataWikiDefinition> list();

    DataWikiDefinition update(DataWikiDefinition dataWiki, long expectedRevision);

    void delete(String id, long expectedRevision);
}

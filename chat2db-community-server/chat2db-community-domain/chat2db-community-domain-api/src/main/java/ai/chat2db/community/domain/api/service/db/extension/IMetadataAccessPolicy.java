package ai.chat2db.community.domain.api.service.db.extension;

import java.util.List;

import ai.chat2db.community.domain.api.model.metadata.extension.MetadataAccessContext;

public interface IMetadataAccessPolicy {

    /**
     * Returns one authorization result for each input resource, preserving input order.
     */
    List<Boolean> authorize(List<MetadataAccessContext> resources);
}

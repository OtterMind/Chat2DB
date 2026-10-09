package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.db.LargeValueToken;

import java.util.Collection;
import ai.chat2db.community.domain.api.model.request.db.DbLargeValueTokensAttachRequest;

/**
 * Manages large-value tokens attached to execution results.
 */
public interface IDbLargeValueTokenService {

    /**
     * Attaches large-value tokens to an execution response.
     *
     * @param dbLargeValueTokensAttachRequest large-value token attachment parameters.
     */
    void attachTokens(DbLargeValueTokensAttachRequest dbLargeValueTokensAttachRequest);

    /**
     * Returns a large-value token or rejects an invalid identifier.
     *
     * @param id large-value token identifier.
     * @return valid large-value token.
     */
    LargeValueToken requireValid(String id);

    /**
     * Releases the given handles and any snapshot that no live handle points at any more.
     * <p>
     * Clients call this when they discard a result set, so captured content is freed right away instead of waiting
     * for the handle lifetime to run out.
     *
     * @param ids handle identifiers to release; unknown identifiers are ignored.
     */
    void releaseTokens(Collection<String> ids);

    /**
     * Frees a sealed snapshot that no handle points at any more, so an execution that produced no usable handle does
     * not keep its captured content until the snapshot TTL.
     *
     * @param snapshotId snapshot identifier.
     */
    void releaseUnusedFor(String snapshotId);
}

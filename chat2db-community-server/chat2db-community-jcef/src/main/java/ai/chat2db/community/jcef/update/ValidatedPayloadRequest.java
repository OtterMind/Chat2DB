package ai.chat2db.community.jcef.update;

import java.net.URI;

/**
 * A payload request that has already passed source-independent validation.
 * It carries no credentials, cookies, or arbitrary headers.
 */
public record ValidatedPayloadRequest(
        String version,
        String assetName,
        URI validatedUri,
        long rangeOffset
) {
}

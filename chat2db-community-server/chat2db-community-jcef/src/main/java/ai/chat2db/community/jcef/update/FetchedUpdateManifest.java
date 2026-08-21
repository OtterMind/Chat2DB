package ai.chat2db.community.jcef.update;

/**
 * Bounded, validated manifest bytes returned by an {@link UpdateSource} together with
 * the basic discovery fields needed for the check phase.
 */
public record FetchedUpdateManifest(
        byte[] exactBytes,
        String version,
        String releaseNotes,
        String releasePageUrl,
        Boolean forceUpdate,
        String metadataSha256,
        long fetchedAtNanos
) {
    public FetchedUpdateManifest(byte[] exactBytes, String version, String releaseNotes, String releasePageUrl,
                                 Boolean forceUpdate, long fetchedAtNanos) {
        this(exactBytes, version, releaseNotes, releasePageUrl, forceUpdate, null, fetchedAtNanos);
    }
}

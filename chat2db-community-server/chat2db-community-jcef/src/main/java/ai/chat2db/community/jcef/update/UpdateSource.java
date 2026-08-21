package ai.chat2db.community.jcef.update;

import java.io.IOException;

public interface UpdateSource {

    FetchedUpdateManifest fetchLatestManifest() throws IOException;

    /** Fetches the versioned, complete payload manifest only after the user starts a download. */
    byte[] fetchVersionManifest(String version) throws IOException;

    UpdateResponse openPayload(ValidatedPayloadRequest request) throws IOException;
}

package ai.chat2db.community.jcef.update;

import java.io.IOException;

public interface UpdateSource {

    FetchedUpdateManifest fetchLatestManifest() throws IOException;

    UpdateResponse openPayload(ValidatedPayloadRequest request) throws IOException;
}

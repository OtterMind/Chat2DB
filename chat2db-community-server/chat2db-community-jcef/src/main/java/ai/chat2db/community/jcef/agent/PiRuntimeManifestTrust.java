package ai.chat2db.community.jcef.agent;

import java.io.IOException;

@FunctionalInterface
public interface PiRuntimeManifestTrust {

    void verify(String platform, byte[] manifest) throws IOException;
}

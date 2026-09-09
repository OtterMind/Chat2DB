package ai.chat2db.community.jcef.agent;

import java.io.IOException;

@FunctionalInterface
public interface PiRuntimeArchiveTrust {

    void verify(String platform, byte[] archive) throws IOException;
}

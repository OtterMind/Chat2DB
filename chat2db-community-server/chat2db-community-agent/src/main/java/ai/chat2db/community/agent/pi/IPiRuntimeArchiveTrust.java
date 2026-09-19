package ai.chat2db.community.agent.pi;

import java.io.IOException;

@FunctionalInterface
public interface IPiRuntimeArchiveTrust {

    void verify(String platform, byte[] archive) throws IOException;
}

package ai.chat2db.community.agent.pi;

import java.io.IOException;

@FunctionalInterface
public interface IPiRuntimePreflight {

    void verify() throws IOException;
}

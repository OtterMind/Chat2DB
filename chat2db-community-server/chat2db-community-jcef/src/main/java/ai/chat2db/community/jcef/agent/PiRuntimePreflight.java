package ai.chat2db.community.jcef.agent;

import java.io.IOException;

@FunctionalInterface
public interface PiRuntimePreflight {

    void verify() throws IOException;
}

package ai.chat2db.community.start.ai.subscription.appserver.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class JdkProcessLauncher implements ProcessLauncher {

    @Override
    public ManagedProcess start(List<String> command, Path workdir, Map<String, String> environment)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workdir.toFile());
        builder.redirectErrorStream(false);
        Map<String, String> env = builder.environment();
        env.clear();
        env.putAll(environment);
        Process process = builder.start();
        return new JdkManagedProcess(process);
    }

    private static final class JdkManagedProcess implements ManagedProcess {
        private final Process process;

        private JdkManagedProcess(Process process) {
            this.process = process;
        }

        @Override
        public OutputStream stdin() {
            return process.getOutputStream();
        }

        @Override
        public InputStream stdout() {
            return process.getInputStream();
        }

        @Override
        public InputStream stderr() {
            return process.getErrorStream();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void destroyForcibly() {
            process.destroyForcibly();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return process.waitFor(timeout, unit);
        }

        @Override
        public Integer exitValueIfTerminated() {
            if (process.isAlive()) {
                return null;
            }
            return process.exitValue();
        }
    }
}

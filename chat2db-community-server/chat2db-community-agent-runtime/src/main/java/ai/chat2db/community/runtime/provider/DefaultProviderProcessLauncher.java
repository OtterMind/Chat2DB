package ai.chat2db.community.runtime.provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DefaultProviderProcessLauncher implements ProviderProcessLauncher {

    @Override
    public ManagedProviderProcess start(List<String> command, Path workingDirectory,
                                        Map<String, String> environment) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(windowsCommand(command));
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().putAll(environment);
        return new JavaManagedProcess(builder.start());
    }

    private List<String> windowsCommand(List<String> command) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") || command.isEmpty()) {
            return command;
        }
        String executable = command.get(0).toLowerCase(Locale.ROOT);
        ArrayList<String> wrapped = new ArrayList<>();
        if (executable.endsWith(".cmd") || executable.endsWith(".bat")) {
            wrapped.addAll(List.of("cmd.exe", "/d", "/s", "/c"));
        } else if (executable.endsWith(".ps1")) {
            wrapped.addAll(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File"));
        } else {
            return command;
        }
        wrapped.addAll(command);
        return wrapped;
    }

    private record JavaManagedProcess(Process delegate) implements ManagedProviderProcess {
        @Override public InputStream stdout() { return delegate.getInputStream(); }
        @Override public InputStream stderr() { return delegate.getErrorStream(); }
        @Override public OutputStream stdin() { return delegate.getOutputStream(); }
        @Override public boolean isAlive() { return delegate.isAlive(); }
        @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
        @Override public boolean waitFor(Duration timeout) throws InterruptedException {
            return delegate.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        @Override public void destroy() { delegate.destroy(); }
        @Override public void destroyForcibly() { delegate.destroyForcibly(); }
        @Override public long pid() { return delegate.pid(); }
        @Override public java.time.Instant startInstant() {
            return delegate.info().startInstant().orElse(null);
        }
    }
}

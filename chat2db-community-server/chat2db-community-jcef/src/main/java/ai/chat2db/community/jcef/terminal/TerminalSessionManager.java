package ai.chat2db.community.jcef.terminal;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import ai.chat2db.community.jcef.utils.CallJsFunctionUtil;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class TerminalSessionManager {
    private static final ConcurrentMap<String, TerminalSession> SESSIONS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService OUTPUT_DISPATCHER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat2db-terminal-output-dispatcher");
        thread.setDaemon(true);
        return thread;
    });
    private static final TerminalEventPublisher JCEF_EVENT_PUBLISHER = TerminalSessionManager::publishToJcef;
    private static volatile TerminalEventPublisher eventPublisher = JCEF_EVENT_PUBLISHER;

    static final String DEFAULT_SHELL_PROPERTY = "chat2db.community.terminal.default-shell";
    private static volatile TerminalProcessFactory processFactory = TerminalSessionManager::defaultProcessFactory;

    private TerminalSessionManager() {
    }

    public static Map<String, Object> create(Path directory, int columns, int rows) throws IOException {
        return create(directory, columns, rows, "system");
    }

    public static Map<String, Object> create(Path directory, int columns, int rows, String shellId) throws IOException {
        return create(directory, columns, rows, resolveShellCandidates(shellId));
    }

    static Map<String, Object> create(Path directory, int columns, int rows, List<ShellCommand> candidates) throws IOException {
        IOException lastFailure = null;
        for (ShellCommand shell : candidates) {
            try {
                return create(directory, columns, rows, shell);
            } catch (IOException failure) {
                // Some corporate security suites (e.g. QiAnXin) deny process creation for
                // powershell.exe even though the binary is present in PATH. Treat a spawn-time
                // failure as "this shell cannot be invoked" and try the next candidate so the
                // terminal still opens via, for example, cmd.exe.
                lastFailure = failure;
                log.warn("Unable to start terminal shell [{}], trying the next available shell",
                        shell.command(), failure);
            }
        }
        throw lastFailure != null
                ? lastFailure
                : new IOException("No terminal shell is available");
    }

    public static Map<String, Object> createInUserHome(int columns, int rows, String shellId) throws IOException {
        return create(resolveUserHomeDirectory(System.getProperty("user.home")), columns, rows, shellId);
    }

    static Path resolveUserHomeDirectory(String configuredUserHome) {
        if (configuredUserHome == null || configuredUserHome.isBlank()) {
            throw new IllegalStateException("User home directory is not available");
        }
        return Path.of(configuredUserHome);
    }

    private static Map<String, Object> create(Path directory, int columns, int rows, ShellCommand shell) throws IOException {
        Path cwd = directory.toRealPath();
        if (!Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("Terminal working directory is not available");
        }

        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("TERM", "xterm-256color");
        environment.put("COLORTERM", "truecolor");
        environment.put("TERM_PROGRAM", "Chat2DB");
        environment.putIfAbsent("LANG", "en_US.UTF-8");
        if (applyColorEnvironment(environment)) {
            applyShellColorEnvironment(environment, shell);
        }

        PtyProcess process = startProcess(shell.command().toArray(String[]::new), cwd.toString(), environment,
                Math.max(columns, 20), Math.max(rows, 5));

        String sessionId = UUID.randomUUID().toString();
        TerminalSession session = new TerminalSession(sessionId, cwd, shell, process);
        SESSIONS.put(sessionId, session);
        session.startOutputPump();

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("cwd", cwd.toString());
        result.put("shell", shell.displayName());
        result.put("shellId", shell.id());
        return result;
    }

    private static PtyProcess startProcess(String[] command, String directory, Map<String, String> environment,
                                           int columns, int rows) throws IOException {
        return processFactory.create(command, directory, environment, columns, rows);
    }

    static PtyProcess defaultProcessFactory(String[] command, String directory, Map<String, String> environment,
                                            int columns, int rows) throws IOException {
        return new PtyProcessBuilder(command)
                .setDirectory(directory)
                .setEnvironment(environment)
                .setInitialColumns(columns)
                .setInitialRows(rows)
                .setRedirectErrorStream(true)
                .setWindowsAnsiColorEnabled(true)
                .start();
    }

    static boolean applyColorEnvironment(Map<String, String> environment) {
        // Selecting a Chat2DB terminal theme is an explicit color opt-in. Keep
        // the override scoped to this child process and avoid exposing CLI tools
        // to the conflicting NO_COLOR + FORCE_COLOR combination.
        environment.remove("NO_COLOR");
        environment.put("FORCE_COLOR", "1");
        environment.put("CLICOLOR_FORCE", "1");
        environment.putIfAbsent("CLICOLOR", "1");
        environment.putIfAbsent("LSCOLORS", "ExGxBxDxCxEgEdxbxgxcxd");
        environment.putIfAbsent("LS_COLORS", "di=34:ln=36:so=35:pi=33:ex=32:bd=33;01:cd=33;01");
        return true;
    }

    public static Map<String, Object> duplicate(String sessionId, int columns, int rows) throws IOException {
        TerminalSession session = requireSession(sessionId);
        return create(session.cwd(), columns, rows, session.shell());
    }

    public static Map<String, Object> capabilities() {
        String os = normalizedOsName();
        List<Map<String, Object>> shells = new ArrayList<>();
        shells.add(shellOption("system", "System default", true));
        addShellOption(shells, "bash", "Bash", findCommand(os, "bash", "bash.exe"));
        addShellOption(shells, "zsh", "Zsh", findCommand(os, "zsh"));
        addShellOption(shells, "pwsh", "PowerShell 7", findCommand(os, "pwsh", "pwsh.exe"));
        if (isWindows(os)) {
            addShellOption(shells, "powershell", "Windows PowerShell", findCommand(os, "powershell.exe"));
            addShellOption(shells, "cmd", "Command Prompt", resolveCommandPrompt());
        }
        return Map.of(
                "os", isWindows(os) ? "windows" : os.contains("mac") ? "mac" : "linux",
                "shells", shells
        );
    }

    public static void write(String sessionId, String data) throws IOException {
        TerminalSession session = requireSession(sessionId);
        synchronized (session.output()) {
            session.output().write(data.getBytes(StandardCharsets.UTF_8));
            session.output().flush();
        }
    }

    public static void resize(String sessionId, int columns, int rows) {
        requireSession(sessionId).process().setWinSize(new WinSize(Math.max(columns, 20), Math.max(rows, 5)));
    }

    public static void attach(String sessionId, String consumerId) {
        requireKnownSession(sessionId).attach(consumerId);
    }

    public static void detach(String sessionId, String consumerId) {
        TerminalSession session = SESSIONS.get(sessionId);
        if (session != null) {
            session.detach(consumerId);
        }
    }

    public static void acknowledgeOutput(String sessionId, long sequence) {
        TerminalSession session = SESSIONS.get(sessionId);
        if (session != null) {
            session.acknowledgeOutput(sequence);
        }
    }

    public static Map<String, Object> status(String sessionId) {
        TerminalSession session = SESSIONS.get(sessionId);
        boolean alive = session != null && session.process().isAlive();
        boolean busy = alive && getAliveDescendants(session.process()).stream().anyMatch(ProcessHandle::isAlive);
        return Map.of("alive", alive, "busy", busy);
    }

    public static Map<String, Map<String, Object>> statuses(List<String> sessionIds) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        sessionIds.forEach(sessionId -> result.put(sessionId, status(sessionId)));
        return result;
    }

    public static void kill(String sessionId) {
        TerminalSession session = SESSIONS.remove(sessionId);
        if (session != null) {
            session.close();
            getAliveDescendants(session.process()).forEach(ProcessHandle::destroy);
            session.process().destroy();
        }
    }

    public static void kill(List<String> sessionIds) {
        sessionIds.forEach(TerminalSessionManager::kill);
    }

    public static void shutdown() {
        List<String> sessionIds = List.copyOf(SESSIONS.keySet());
        sessionIds.forEach(TerminalSessionManager::kill);
        OUTPUT_DISPATCHER.shutdownNow();
    }

    private static List<ProcessHandle> getAliveDescendants(PtyProcess process) {
        try {
            return ProcessHandle.of(process.pid())
                    .map(handle -> handle.descendants().filter(ProcessHandle::isAlive).toList())
                    .orElseGet(List::of);
        } catch (UnsupportedOperationException | SecurityException exception) {
            log.debug("Unable to inspect terminal child processes", exception);
            return List.of();
        }
    }

    private static TerminalSession requireSession(String sessionId) {
        TerminalSession session = requireKnownSession(sessionId);
        if (session == null || !session.process().isAlive()) {
            throw new IllegalArgumentException("Terminal session is not available");
        }
        return session;
    }

    private static TerminalSession requireKnownSession(String sessionId) {
        TerminalSession session = SESSIONS.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Terminal session is not available");
        }
        return session;
    }

    static List<ShellCommand> resolveShellCandidates(String requestedShellId) {
        String shellId = requestedShellId == null || requestedShellId.isBlank()
                ? "system"
                : requestedShellId.toLowerCase(Locale.ROOT);
        if ("system".equals(shellId)) {
            // The default shell may be denied at runtime (e.g. powershell.exe blocked by a
            // corporate security suite). Return the ordered fallback chain so create(...) can
            // move on to cmd.exe when an earlier candidate cannot be started.
            return resolveSystemShellCandidates(normalizedOsName());
        }
        return List.of(resolveShell(shellId));
    }

    private static ShellCommand resolveShell(String requestedShellId) {
        String shellId = requestedShellId == null || requestedShellId.isBlank()
                ? "system"
                : requestedShellId.toLowerCase(Locale.ROOT);
        String os = normalizedOsName();
        if ("system".equals(shellId)) {
            return resolveSystemShell(os);
        }
        return switch (shellId) {
            case "bash" -> unixShell("bash", "Bash", requireCommand(os, "Bash", "bash", "bash.exe"));
            case "zsh" -> unixShell("zsh", "Zsh", requireCommand(os, "Zsh", "zsh"));
            case "pwsh" -> powerShell("pwsh", "PowerShell 7", requireCommand(os, "PowerShell 7", "pwsh", "pwsh.exe"));
            case "powershell" -> powerShell(
                    "powershell",
                    "Windows PowerShell",
                    requireCommand(os, "Windows PowerShell", "powershell.exe")
            );
            case "cmd" -> commandPrompt("cmd", requireCommand(os, "Command Prompt", resolveCommandPrompt()));
            default -> throw new IllegalArgumentException("Unsupported terminal shell: " + shellId);
        };
    }

    private static ShellCommand resolveSystemShell(String os) {
        return resolveSystemShellCandidates(os).get(0);
    }

    private static List<ShellCommand> resolveSystemShellCandidates(String os) {
        return resolveSystemShellCandidates(os, TerminalSessionManager::findCommand);
    }

    static List<ShellCommand> resolveSystemShellCandidates(String os, CommandResolver resolver) {
        List<ShellCommand> candidates = new ArrayList<>();
        Set<String> seenCommands = new HashSet<>();
        String configuredShell = configuredDefaultShell();
        if (configuredShell != null) {
            ShellCommand leading = resolveConfiguredShell(configuredShell);
            if (leading != null) {
                candidates.add(leading);
                seenCommands.add(leading.command().get(0));
            }
        }
        if (isWindows(os)) {
            String pwsh = resolver.find(os, "pwsh.exe", "pwsh");
            if (pwsh != null) {
                addCandidate(candidates, seenCommands, powerShell("system", "PowerShell 7", pwsh));
            }
            String powerShell = resolver.find(os, "powershell.exe");
            if (powerShell != null) {
                addCandidate(candidates, seenCommands, powerShell("system", "Windows PowerShell", powerShell));
            }
            // Keep cmd.exe as the last resort so a blocked PowerShell still yields a usable shell.
            String cmd = resolver.find(os, resolveCommandPrompt());
            addCandidate(candidates, seenCommands, commandPrompt("system", cmd == null ? resolveCommandPrompt() : cmd));
            return candidates;
        }
        ShellCommand systemShell = resolveNonWindowsSystemShell(os);
        if (seenCommands.add(systemShell.command().get(0))) {
            candidates.add(systemShell);
        }
        return candidates;
    }

    static ShellCommand commandPrompt(String id, String command) {
        // /K keeps cmd.exe resident after launch, matching pwsh's -NoExit behaviour.
        return new ShellCommand(id, List.of(command, "/K"), "Command Prompt", ShellFamily.CMD);
    }

    static String configuredDefaultShell() {
        String configured = System.getProperty(DEFAULT_SHELL_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        // "system" means auto resolution; treating it as a configured default would loop back here.
        return "system".equals(normalized) ? null : normalized;
    }

    private static ShellCommand resolveConfiguredShell(String shellId) {
        try {
            return resolveShell(shellId);
        } catch (IllegalArgumentException exception) {
            log.warn("Ignoring configured default terminal shell [{}]: {}", shellId, exception.getMessage());
            return null;
        }
    }

    private static void addCandidate(List<ShellCommand> candidates, Set<String> seenCommands, ShellCommand shell) {
        if (seenCommands.add(shell.command().get(0))) {
            candidates.add(shell);
        }
    }

    private static ShellCommand resolveNonWindowsSystemShell(String os) {
        String configuredShell = System.getenv("SHELL");
        if (configuredShell != null && Files.isExecutable(Path.of(configuredShell))) {
            return unixShell("system", Path.of(configuredShell).getFileName().toString(), configuredShell);
        }
        String fallbackName = os.contains("mac") ? "zsh" : "bash";
        return unixShell("system", fallbackName, requireCommand(os, fallbackName, fallbackName));
    }

    static void applyShellColorEnvironment(Map<String, String> environment, ShellCommand shell) {
        switch (shell.family()) {
            case BASH -> environment.put("PS1", "\\[\\033[34m\\]\\u@\\h\\[\\033[0m\\]:"
                    + "\\[\\033[32m\\]\\w\\[\\033[0m\\]\\$ ");
            case ZSH -> environment.put("PROMPT", "%F{blue}%n@%m%f:%F{green}%~%f %# ");
            case CMD -> environment.put("PROMPT", "$E[34m$P$E[0m$G$S");
            default -> {
                // PowerShell uses PSReadLine colors; other shells keep their native prompt configuration.
            }
        }
    }

    static ShellCommand unixShell(String id, String displayName, String command) {
        String executableName = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        ShellFamily family = executableName.contains("zsh")
                ? ShellFamily.ZSH
                : executableName.contains("bash") ? ShellFamily.BASH : ShellFamily.OTHER;
        return new ShellCommand(id, List.of(command, "-l"), displayName, family);
    }

    static ShellCommand powerShell(String id, String displayName, String command) {
        String colorSetup = "if (Get-Command Set-PSReadLineOption -ErrorAction SilentlyContinue) { "
                + "Set-PSReadLineOption -Colors @{ "
                + "Command = 'Blue'; Parameter = 'Cyan'; String = 'Green'; "
                + "Operator = 'Yellow'; Variable = 'Magenta'; Number = 'Yellow' } }";
        return new ShellCommand(
                id,
                List.of(command, "-NoLogo", "-NoExit", "-Command", colorSetup),
                displayName,
                ShellFamily.POWERSHELL
        );
    }

    private static Map<String, Object> shellOption(String id, String label, boolean available) {
        return Map.of("id", id, "label", label, "available", available);
    }

    private static void addShellOption(List<Map<String, Object>> options, String id, String label, String command) {
        if (command != null) {
            options.add(shellOption(id, label, true));
        }
    }

    private static String requireCommand(String os, String label, String... candidates) {
        String command = findCommand(os, candidates);
        if (command == null) {
            throw new IllegalArgumentException(label + " is not installed or is not available in PATH");
        }
        return command;
    }

    private static String resolveCommandPrompt() {
        return System.getenv().getOrDefault("ComSpec", "cmd.exe");
    }

    private static String findCommand(String os, String... candidates) {
        for (String candidate : candidates) {
            Path directPath = Path.of(candidate);
            if (directPath.isAbsolute() && Files.isRegularFile(directPath) && Files.isExecutable(directPath)) {
                return directPath.toString();
            }
            String pathValue = System.getenv("PATH");
            if (pathValue == null || pathValue.isBlank()) {
                continue;
            }
            for (String pathEntry : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (pathEntry.isBlank()) {
                    continue;
                }
                Path executable = Path.of(pathEntry, candidate);
                if (Files.isRegularFile(executable) && (isWindows(os) || Files.isExecutable(executable))) {
                    return executable.toString();
                }
            }
        }
        return null;
    }

    private static String normalizedOsName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isWindows(String os) {
        return os.contains("win");
    }

    private static void publish(String sessionId, ActionTypeEnum actionType, Map<String, Object> message) {
        eventPublisher.publish(sessionId, actionType, message);
    }

    private static void publishToJcef(String sessionId, ActionTypeEnum actionType, Map<String, Object> message) {
        ConsoleResult result = new ConsoleResult();
        result.setUuid(sessionId);
        result.setActionType(actionType.getName());
        result.setMessage(message);
        CallJsFunctionUtil.callHandleJavaMessage(
                JcefContext.getInstance().getBrowser_(),
                JSON.toJSONString(result)
        );
    }

    static void setEventPublisherForTests(TerminalEventPublisher publisher) {
        eventPublisher = publisher == null ? JCEF_EVENT_PUBLISHER : publisher;
    }

    static void resetEventPublisherForTests() {
        eventPublisher = JCEF_EVENT_PUBLISHER;
    }

    static void setProcessFactoryForTests(TerminalProcessFactory factory) {
        processFactory = factory == null ? TerminalSessionManager::defaultProcessFactory : factory;
    }

    static void resetProcessFactoryForTests() {
        processFactory = TerminalSessionManager::defaultProcessFactory;
    }

    @FunctionalInterface
    interface CommandResolver {
        String find(String os, String... candidates);
    }

    @FunctionalInterface
    interface TerminalProcessFactory {
        PtyProcess create(String[] command, String directory, Map<String, String> environment,
                          int columns, int rows) throws IOException;
    }

    @FunctionalInterface
    interface TerminalEventPublisher {
        void publish(String sessionId, ActionTypeEnum actionType, Map<String, Object> message);
    }

    enum ShellFamily {
        BASH,
        ZSH,
        POWERSHELL,
        CMD,
        OTHER
    }

    record ShellCommand(String id, List<String> command, String displayName, ShellFamily family) {
    }

    private static final class TerminalSession {
        private static final int MAX_PENDING_CHARACTERS = 1024 * 1024;
        private static final int MAX_BATCH_CHARACTERS = 32 * 1024;
        private static final long FLUSH_DELAY_MILLIS = 12;
        private final String id;
        private final Path cwd;
        private final ShellCommand shell;
        private final PtyProcess process;
        private final StringBuilder pendingOutput = new StringBuilder();
        private final Set<String> consumers = new HashSet<>();
        private OutputBatch inFlightOutput;
        private long nextSequence;
        private boolean flushScheduled;
        private boolean closed;
        private Integer exitCode;

        private TerminalSession(String id, Path cwd, ShellCommand shell, PtyProcess process) {
            this.id = id;
            this.cwd = cwd;
            this.shell = shell;
            this.process = process;
        }

        Path cwd() {
            return cwd;
        }

        ShellCommand shell() {
            return shell;
        }

        PtyProcess process() {
            return process;
        }

        OutputStream output() {
            return process.getOutputStream();
        }

        void attach(String consumerId) {
            OutputBatch replay;
            synchronized (this) {
                boolean wasDetached = consumers.isEmpty();
                consumers.add(consumerId);
                replay = wasDetached ? inFlightOutput : null;
                if (replay == null) {
                    scheduleFlushLocked(0);
                }
            }
            if (replay != null) {
                publishOutputBatch(replay);
            }
        }

        synchronized void detach(String consumerId) {
            consumers.remove(consumerId);
        }

        synchronized void acknowledgeOutput(long sequence) {
            if (inFlightOutput == null || inFlightOutput.sequence() != sequence) {
                return;
            }
            inFlightOutput = null;
            notifyAll();
            scheduleFlushLocked(0);
        }

        synchronized void publishOutput(String data) throws InterruptedException {
            while (!closed && !consumers.isEmpty()
                    && bufferedCharacters() + data.length() > MAX_PENDING_CHARACTERS) {
                wait();
            }
            if (closed) {
                return;
            }
            pendingOutput.append(data);
            if (consumers.isEmpty()) {
                trimDetachedOutput();
            } else {
                scheduleFlushLocked(FLUSH_DELAY_MILLIS);
            }
        }

        private synchronized int bufferedCharacters() {
            return pendingOutput.length() + (inFlightOutput == null ? 0 : inFlightOutput.data().length());
        }

        private void trimDetachedOutput() {
            int inFlightCharacters = inFlightOutput == null ? 0 : inFlightOutput.data().length();
            int pendingLimit = Math.max(MAX_PENDING_CHARACTERS - inFlightCharacters, 0);
            if (pendingOutput.length() > pendingLimit) {
                pendingOutput.delete(0, pendingOutput.length() - pendingLimit);
            }
        }

        private void scheduleFlushLocked(long delayMillis) {
            if (closed || flushScheduled || consumers.isEmpty()) {
                return;
            }
            flushScheduled = true;
            OUTPUT_DISPATCHER.schedule(this::flushOutput, delayMillis, TimeUnit.MILLISECONDS);
        }

        private void flushOutput() {
            OutputBatch batch = null;
            Integer completedExitCode = null;
            synchronized (this) {
                flushScheduled = false;
                if (closed || consumers.isEmpty() || inFlightOutput != null) {
                    return;
                }
                if (!pendingOutput.isEmpty()) {
                    int count = Math.min(pendingOutput.length(), MAX_BATCH_CHARACTERS);
                    batch = new OutputBatch(++nextSequence, pendingOutput.substring(0, count));
                    pendingOutput.delete(0, count);
                    inFlightOutput = batch;
                } else if (exitCode != null) {
                    closed = true;
                    completedExitCode = exitCode;
                }
            }
            if (batch != null) {
                publishOutputBatch(batch);
            } else if (completedExitCode != null) {
                SESSIONS.remove(id, this);
                publish(id, ActionTypeEnum.TERMINAL_EXIT, Map.of("exitCode", completedExitCode));
            }
        }

        private void publishOutputBatch(OutputBatch batch) {
            publish(id, ActionTypeEnum.TERMINAL_OUTPUT, Map.of(
                    "data", batch.data(),
                    "sequence", batch.sequence()
            ));
        }

        synchronized void finish(int completedExitCode) {
            if (closed) {
                return;
            }
            exitCode = completedExitCode;
            scheduleFlushLocked(0);
        }

        synchronized void close() {
            closed = true;
            consumers.clear();
            pendingOutput.setLength(0);
            inFlightOutput = null;
            notifyAll();
        }

        void startOutputPump() {
            Thread outputThread = new Thread(() -> {
                try (InputStreamReader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = reader.read(buffer)) >= 0) {
                        if (count > 0) {
                            publishOutput(new String(buffer, 0, count));
                        }
                    }
                } catch (IOException exception) {
                    if (process.isAlive()) {
                        log.warn("Failed to read terminal session {}", id, exception);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    int completedExitCode = -1;
                    try {
                        completedExitCode = process.waitFor();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    finish(completedExitCode);
                }
            }, "chat2db-terminal-" + id);
            outputThread.setDaemon(true);
            outputThread.start();
        }

        private record OutputBatch(long sequence, String data) {
        }
    }
}

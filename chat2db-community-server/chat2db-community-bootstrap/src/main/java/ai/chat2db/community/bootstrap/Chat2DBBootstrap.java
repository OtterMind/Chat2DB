package ai.chat2db.community.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class Chat2DBBootstrap {

    private static final String PRECHECK_ARGUMENT = "--chat2db-update-precheck";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Chat2DBBootstrap() {
    }

    public static void main(String[] args) throws Exception {
        Path bootstrapJar = Path.of(Chat2DBBootstrap.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        Path appDirectory = bootstrapJar.getParent().getParent();
        LaunchConfiguration configuration = loadConfiguration(appDirectory);
        ValidatedLaunch launch = validate(appDirectory, configuration);
        System.setProperty("chat2db.install.root", resolveInstallTarget(appDirectory).toString());
        if (Arrays.asList(args).contains(PRECHECK_ARGUMENT)) {
            precheck(launch);
            return;
        }
        launch(launch, args);
    }

    static LaunchConfiguration loadConfiguration(Path appDirectory) throws Exception {
        Path configurationFile = safeResolve(appDirectory, "runtime/launch.json");
        if (!Files.isRegularFile(configurationFile)) {
            throw new IllegalStateException("Runtime launch configuration is missing: " + configurationFile);
        }
        return OBJECT_MAPPER.readValue(configurationFile.toFile(), LaunchConfiguration.class);
    }

    static ValidatedLaunch validate(Path appDirectory, LaunchConfiguration configuration) {
        if (configuration == null || isBlank(configuration.mainJar()) || isBlank(configuration.mainClass())) {
            throw new IllegalArgumentException("Runtime launch configuration is incomplete");
        }
        Path mainJar = requireFile(safeResolve(appDirectory, configuration.mainJar()), "Runtime main jar");
        List<Path> loaderPaths = new ArrayList<>();
        for (String path : list(configuration.loaderPath())) {
            loaderPaths.add(requireDirectory(safeResolve(appDirectory, path), "Runtime loader path"));
        }
        for (String path : list(configuration.requiredPaths())) {
            Path required = safeResolve(appDirectory, path);
            if (!Files.exists(required)) {
                throw new IllegalStateException("Required runtime path is missing: " + required);
            }
        }
        return new ValidatedLaunch(
            appDirectory.toAbsolutePath().normalize(),
            mainJar,
            configuration.mainClass(),
            List.copyOf(loaderPaths),
            configuration.systemProperties() == null ? Map.of() : Map.copyOf(configuration.systemProperties())
        );
    }

    static void precheck(ValidatedLaunch launch) throws Exception {
        try (URLClassLoader classLoader = runtimeClassLoader(launch)) {
            Class.forName(launch.mainClass(), false, classLoader);
        }
    }

    static void launch(ValidatedLaunch launch, String[] args) throws Exception {
        launch.systemProperties().forEach(System::setProperty);
        if (!launch.loaderPaths().isEmpty()) {
            String loaderPath = String.join(",", launch.loaderPaths().stream().map(Path::toString).toList());
            System.setProperty("loader.path", loaderPath);
        }
        URLClassLoader classLoader = runtimeClassLoader(launch);
        Thread.currentThread().setContextClassLoader(classLoader);
        if (launch.mainClass().startsWith("org.springframework.boot.loader.launch.")) {
            RuntimeUrlStreamHandlerProvider.register(classLoader);
        }
        try {
            Class<?> mainClass = Class.forName(launch.mainClass(), true, classLoader);
            Method main = mainClass.getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }

    private static URLClassLoader runtimeClassLoader(ValidatedLaunch launch) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(launch.mainJar().toUri().toURL());
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static Path safeResolve(Path root, String child) {
        if (isBlank(child)) {
            throw new IllegalArgumentException("Runtime path is required");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(child).normalize();
        if (!result.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Runtime path escapes the app directory: " + child);
        }
        return result;
    }

    private static Path requireFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(label + " is missing: " + path);
        }
        return path;
    }

    private static Path requireDirectory(Path path, String label) {
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(label + " is missing: " + path);
        }
        return path;
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static Path resolveInstallTarget(Path appDirectory) {
        String configured = System.getenv("CHAT2DB_INSTALL_TARGET");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String appImage = System.getenv("APPIMAGE");
        if (appImage != null && !appImage.isBlank()) {
            return Path.of(appImage).toAbsolutePath().normalize();
        }
        Path parent = appDirectory.getParent();
        if (parent != null && "Contents".equals(parent.getFileName().toString())
                && parent.getParent() != null && parent.getParent().getFileName().toString().endsWith(".app")) {
            return parent.getParent();
        }
        if (parent != null && "lib".equals(parent.getFileName().toString()) && parent.getParent() != null) {
            return parent.getParent();
        }
        if (parent == null) {
            throw new IllegalStateException("Cannot resolve full install target from " + appDirectory);
        }
        return parent;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record ValidatedLaunch(
        Path appDirectory,
        Path mainJar,
        String mainClass,
        List<Path> loaderPaths,
        Map<String, String> systemProperties
    ) {
    }
}

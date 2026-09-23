package ai.chat2db.community.updater.v2.installation;

import ai.chat2db.community.updater.v2.enums.UpdatePackageTypeEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePlatformEnum;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class UpdateLayout {

    private static final Pattern SAFE_OPERATION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path installTarget;
    private final Path appDirectory;
    private final Path cacheRoot;
    private final Path supportRoot;

    public UpdateLayout(Path installTarget) {
        this(installTarget, defaultAppDirectory(installTarget), "COMMUNITY");
    }

    public UpdateLayout(Path installTarget, String product) {
        this(installTarget, defaultAppDirectory(installTarget), product);
    }

    public UpdateLayout(Path installTarget, Path appDirectory) {
        this(installTarget, appDirectory, "COMMUNITY");
    }

    public UpdateLayout(Path installTarget, Path appDirectory, String product) {
        this(
            installTarget,
            appDirectory,
            defaultCacheBase().resolve(productDirectory(product)),
            defaultSupportBase().resolve(productDirectory(product))
        );
    }

    public UpdateLayout(Path installTarget, Path appDirectory, Path cacheRoot, Path supportRoot) {
        this.installTarget = normalizeRequired(installTarget, "Install target is required");
        this.appDirectory = normalizeRequired(appDirectory, "Current app directory is required");
        this.cacheRoot = normalizeRequired(cacheRoot, "Update cache root is required");
        this.supportRoot = normalizeRequired(supportRoot, "Update support root is required");
        Path parent = this.installTarget.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Install target must have a parent directory: " + this.installTarget);
        }
        requireOutsideInstallTarget(this.cacheRoot, "Update cache root");
        requireOutsideInstallTarget(this.supportRoot, "Update support root");
    }

    public static UpdateLayout fromCurrentAppDirectory(Path currentAppDirectory, UpdatePlatformEnum platform,
            UpdatePackageTypeEnum packageType, String product) {
        Path normalized = normalizeRequired(currentAppDirectory, "Current app directory is required");
        if (normalized.getFileName() == null || !"app".equals(normalized.getFileName().toString())) {
            throw new IllegalArgumentException("Current application directory must be named app: " + normalized);
        }
        if (packageType == UpdatePackageTypeEnum.LINUX_APPIMAGE) {
            String appImage = System.getenv("APPIMAGE");
            if (appImage == null || appImage.isBlank()) {
                throw new IllegalStateException("APPIMAGE is required for an AppImage update");
            }
            return new UpdateLayout(Path.of(appImage), normalized, product);
        }
        Path installTarget;
        if (platform == UpdatePlatformEnum.MACOS) {
            Path contents = normalized.getParent();
            installTarget = contents == null ? null : contents.getParent();
            if (contents == null || installTarget == null || !"Contents".equals(contents.getFileName().toString())
                    || !installTarget.getFileName().toString().endsWith(".app")) {
                throw new IllegalArgumentException("Cannot resolve macOS application bundle from " + normalized);
            }
        } else if (platform == UpdatePlatformEnum.LINUX && normalized.getParent() != null
                && normalized.getParent().getFileName() != null
                && "lib".equals(normalized.getParent().getFileName().toString())) {
            installTarget = normalized.getParent().getParent();
        } else {
            installTarget = normalized.getParent();
        }
        if (installTarget == null) {
            throw new IllegalArgumentException("Cannot resolve full install target from " + normalized);
        }
        return new UpdateLayout(installTarget, normalized, product);
    }

    public Path installRoot() {
        return installTarget;
    }

    public Path installTarget() {
        return installTarget;
    }

    public Path appDirectory() {
        return appDirectory;
    }

    public Path updateRoot() {
        return supportRoot;
    }

    public Path updateBase() {
        return supportRoot.getParent();
    }

    public Path cacheRoot() {
        return cacheRoot;
    }

    public Path supportRoot() {
        return supportRoot;
    }

    public Path preferencesFile() {
        return supportRoot.resolve("preferences.json");
    }

    public Path logsDirectory() {
        return supportRoot.resolve("logs");
    }

    public Path auditLogFile(String operationId) {
        requireSafeOperationId(operationId);
        return logsDirectory().resolve("update-" + operationId + ".log");
    }

    /**
     * Compatibility overload for callers that still pass the operation id. All
     * operations now append to the one product-level audit log.
     */
    public Path cacheDirectory() {
        return cacheRoot.resolve("update");
    }

    public Path updateWorkspace() {
        return cacheDirectory();
    }

    public Path healthFile(String transactionId) {
        requireSafeOperationId(transactionId);
        return updateWorkspace().resolve("health-" + transactionId + ".json");
    }

    private static void requireSafeOperationId(String operationId) {
        if (operationId == null || !SAFE_OPERATION_ID.matcher(operationId).matches()) {
            throw new IllegalArgumentException("Update operation id contains unsafe path characters");
        }
    }

    public Path cachedPackage(UpdatePackageTypeEnum packageType) {
        return updateWorkspace().resolve("package." + packageType.fileExtension());
    }

    public Path stagedPackage(UpdatePackageTypeEnum packageType) {
        return packageType == UpdatePackageTypeEnum.MACOS_APP_ARCHIVE
            ? stagingDirectory().resolve("package")
            : stagingDirectory().resolve("package." + packageType.fileExtension());
    }

    /**
     * Persisted description of a downloaded, staged update: the signed manifest plus
     * the transaction it belongs to, so a later session can install it without
     * downloading the package again.
     */
    public Path preparedUpdateFile() {
        return updateWorkspace().resolve("prepared-update.json");
    }

    public Path stagingDirectory() {
        return updateWorkspace().resolve("candidate");
    }

    /**
     * Same-volume backup of the installed package. The switch renames the
     * installed package here before copying the candidate, so a failed switch or
     * a candidate that never becomes healthy can be rolled back.
     */
    public Path previousPackage() {
        Path target = installTarget();
        Path name = target.getFileName();
        if (name == null) {
            throw new IllegalStateException("Install target has no file name: " + target);
        }
        return target.resolveSibling(name + ".chat2db-previous");
    }

    public Path workDirectory() {
        return updateWorkspace().resolve("helper");
    }

    private void requireOutsideInstallTarget(Path path, String label) {
        if (path.startsWith(installTarget)) {
            throw new IllegalArgumentException(label + " must be outside the install target");
        }
    }

    private static Path defaultAppDirectory(Path installTarget) {
        Path normalized = normalizeRequired(installTarget, "Install target is required");
        if (normalized.getFileName() != null && normalized.getFileName().toString().endsWith(".app")) {
            return normalized.resolve("Contents/app");
        }
        return normalized.resolve("app");
    }

    private static Path defaultCacheBase() {
        Path home = userHome();
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return home.resolve("Library/Caches/Chat2DB/Updater");
        }
        if (osName.contains("win")) {
            return environmentPath("LOCALAPPDATA", home.resolve("AppData/Local")).resolve("Chat2DB/Updater");
        }
        return environmentPath("XDG_CACHE_HOME", home.resolve(".cache")).resolve("Chat2DB/Updater");
    }

    private static Path defaultSupportBase() {
        Path home = userHome();
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return home.resolve("Library/Application Support/Chat2DB/Updater");
        }
        if (osName.contains("win")) {
            return environmentPath("APPDATA", home.resolve("AppData/Roaming")).resolve("Chat2DB/Updater");
        }
        return environmentPath("XDG_DATA_HOME", home.resolve(".local/share")).resolve("Chat2DB/Updater");
    }

    private static Path environmentPath(String name, Path fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    private static Path userHome() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("User home is required for desktop updates");
        }
        return Path.of(home).toAbsolutePath().normalize();
    }

    private static String productDirectory(String product) {
        Objects.requireNonNull(product, "Update product is required");
        if (!product.matches("[A-Z][A-Z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid update product: " + product);
        }
        return product.toLowerCase(Locale.ROOT);
    }

    private static Path normalizeRequired(Path path, String message) {
        return Objects.requireNonNull(path, message).toAbsolutePath().normalize();
    }
}

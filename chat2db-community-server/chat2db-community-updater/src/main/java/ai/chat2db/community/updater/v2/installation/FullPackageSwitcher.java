package ai.chat2db.community.updater.v2.installation;

import ai.chat2db.community.updater.v2.enums.UpdatePackageTypeEnum;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;

public final class FullPackageSwitcher {

    private final UpdateLayout layout;

    public FullPackageSwitcher(UpdateLayout layout) {
        this.layout = layout;
    }

    /**
     * Replaces the installed package with the staged candidate and returns the
     * backup that must be kept until the candidate has proven healthy. The
     * installed package is moved aside instead of being deleted first, so a
     * failure while copying the candidate can still be rolled back.
     */
    public Path switchToCandidate(String operationId, UpdatePackageTypeEnum packageType) {
        if (!packageType.directReplacement()) {
            throw new IllegalArgumentException("Native installer package cannot use direct replacement: " + packageType);
        }
        Path currentPackage = layout.installTarget();
        Path stagedPackage = layout.stagedPackage(packageType);
        Path backup = layout.previousPackage();
        requirePackage(currentPackage, packageType, "Current full package is missing");
        requirePackage(stagedPackage, packageType, "Staged full package is missing");
        // A precondition, not a switch failure: the caller must not lose the only working copy.
        boolean leftoverBackup = exists(backup, packageType);
        if (leftoverBackup) {
            requireUsableInstalledPackage(backup, packageType);
        }
        try {
            if (leftoverBackup) {
                deleteRecursively(backup);
            }
            movePackage(currentPackage, backup);
            copyPackage(stagedPackage, currentPackage);
            requirePackage(currentPackage, packageType, "Copied candidate package is missing");
            return backup;
        } catch (Exception exception) {
            if (restoreQuietly(backup, currentPackage, packageType)) {
                throw new IllegalStateException("Cannot switch full application package", exception);
            }
            throw new IllegalStateException("Cannot switch full application package; the previous "
                + "package is kept at " + backup, exception);
        }
    }

    /**
     * Restores the package that was installed before {@link #switchToCandidate}.
     * Returns false when no backup is left, for example after a committed
     * transaction.
     */
    public boolean rollback(String operationId, UpdatePackageTypeEnum packageType) {
        Path backup = layout.previousPackage();
        if (!exists(backup, packageType)) {
            return false;
        }
        try {
            deleteRecursively(layout.installTarget());
            movePackage(backup, layout.installTarget());
            requirePackage(layout.installTarget(), packageType, "Rolled back package is missing");
            return true;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot roll back full application package", exception);
        }
    }

    public void commit(String operationId) {
        try {
            deleteRecursively(layout.stagingDirectory());
            deleteRecursively(layout.previousPackage());
            for (UpdatePackageTypeEnum packageType : UpdatePackageTypeEnum.values()) {
                Files.deleteIfExists(layout.cachedPackage(packageType));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot clean committed full-package transaction", exception);
        }
    }

    /**
     * A leftover backup is only dropped when the installed package still looks
     * usable. Otherwise the backup may be the last working copy, for example
     * after a switch that was interrupted before the candidate was copied.
     */
    private void requireUsableInstalledPackage(Path backup, UpdatePackageTypeEnum packageType) {
        if (packageType.singleFile() || Files.isRegularFile(layout.appDirectory().resolve("version.json"))) {
            return;
        }
        throw new IllegalStateException("A previous package backup exists and the installed package has no "
            + "version metadata; refusing to switch: " + backup);
    }

    /**
     * Puts the moved-aside package back after a failed switch.
     *
     * @return true when the install target holds the previous package again, false
     *         when the backup is still the only usable copy
     */
    private boolean restoreQuietly(Path backup, Path currentPackage, UpdatePackageTypeEnum packageType) {
        if (!exists(backup, packageType)) {
            return false;
        }
        try {
            deleteRecursively(currentPackage);
            movePackage(backup, currentPackage);
            requirePackage(currentPackage, packageType, "Restored package is missing");
            return true;
        } catch (Exception restoreFailure) {
            // The original failure stays primary; the backup is kept for recovery.
            return false;
        }
    }

    private static boolean exists(Path path, UpdatePackageTypeEnum type) {
        return type.singleFile()
            ? Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * The backup lives next to the install target, so this is a rename on the same
     * volume and never a partial copy. A copy fallback would make the backup look
     * present while it is incomplete, and the restore path would then move that
     * incomplete copy over the installed package.
     */
    private static void movePackage(Path source, Path target) throws IOException {
        Files.move(source, target);
    }

    private static void requirePackage(Path path, UpdatePackageTypeEnum type, String message) {
        boolean present = type.singleFile() ? Files.isRegularFile(path) : Files.isDirectory(path);
        if (!present) {
            throw new IllegalStateException(message + ": " + path);
        }
    }

    private static void copyPackage(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source)) {
            Files.copy(source, target, LinkOption.NOFOLLOW_LINKS);
            return;
        }
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path destination = target.resolve(source.relativize(directory));
                Files.createDirectory(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path destination = target.resolve(source.relativize(file));
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}

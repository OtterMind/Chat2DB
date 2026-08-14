package ai.chat2db.community.jcef.update;

import java.io.IOException;

/** Performs read-only update discovery and validates the discovery manifest contract. */
final class UpdateChecker {

    private static final String GITHUB_HOST = "github.com";
    private static final String GITHUB_REPOSITORY = "OtterMind/Chat2DB";

    private final UpdateSource updateSource;
    private final LocalVersionStore localVersionStore;

    UpdateChecker(UpdateSource updateSource, LocalVersionStore localVersionStore) {
        this.updateSource = updateSource;
        this.localVersionStore = localVersionStore;
    }

    UpdateCheckOutcome check() throws IOException {
        FetchedUpdateManifest manifest = updateSource.fetchLatestManifest();
        validateVersion(manifest.version());
        validateReleasePageUrl(manifest.releasePageUrl(), manifest.version());
        if (manifest.forceUpdate() == null || Boolean.TRUE.equals(manifest.forceUpdate())) {
            throw new IOException("Remote GitHub manifest must declare forceUpdate=false");
        }
        if (manifest.releaseNotes() != null && manifest.releaseNotes().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IOException("Release notes exceed the 64 KiB limit");
        }
        VersionMetadata local = localVersionStore.load(false);
        String localVersion = local == null ? null : local.getVersion();
        boolean needsUpdate = localVersion == null || localVersion.isBlank()
                || compareVersions(manifest.version(), localVersion) > 0;
        AvailableSnapshot snapshot = needsUpdate
                ? new AvailableSnapshot(manifest.version(), manifest.exactBytes(), manifest.fetchedAtNanos()) : null;
        return new UpdateCheckOutcome(needsUpdate, manifest.releaseNotes(), snapshot, manifest.releasePageUrl());
    }

    static void validateVersion(String version) throws IOException {
        if (version == null || version.trim().isEmpty()) {
            throw new IOException("Latest manifest version is blank");
        }
        if (!version.matches("[0-9]+(\\.[0-9]+)*")) {
            throw new IOException("Latest manifest version is not a numeric SemVer: " + version);
        }
    }

    static int compareVersions(String version1, String version2) {
        String[] first = normalize(version1).split("\\.");
        String[] second = normalize(version2).split("\\.");
        for (int index = 0; index < Math.max(first.length, second.length); index++) {
            int left = index < first.length ? parsePart(first[index]) : 0;
            int right = index < second.length ? parsePart(second[index]) : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return 0;
    }

    private static void validateReleasePageUrl(String releasePageUrl, String version) throws IOException {
        if (releasePageUrl == null || releasePageUrl.trim().isEmpty()) {
            return;
        }
        String expected = "https://" + GITHUB_HOST + "/" + GITHUB_REPOSITORY + "/releases/tag/v" + version;
        if (!expected.equals(releasePageUrl)) {
            throw new IOException("Release page URL does not match the manifest version: " + releasePageUrl);
        }
    }

    private static String normalize(String version) {
        if (version == null || version.trim().isEmpty()) {
            return "0";
        }
        String normalized = version.trim();
        return normalized.startsWith("v") || normalized.startsWith("V") ? normalized.substring(1) : normalized;
    }

    private static int parsePart(String part) {
        StringBuilder digits = new StringBuilder();
        for (int index = 0; index < part.length() && Character.isDigit(part.charAt(index)); index++) {
            digits.append(part.charAt(index));
        }
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
    }
}

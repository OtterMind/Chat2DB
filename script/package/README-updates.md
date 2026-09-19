# Desktop update packages

Community desktop checks the stable index at
`https://github.com/OtterMind/Chat2DB/releases/latest/download/release-index.json`.
When **Receive Beta versions** is enabled, it also checks
`https://raw.githubusercontent.com/OtterMind/Chat2DB/community-beta-index/release-index.json`
and selects the highest eligible Stable or Beta version. The preference is off
by default and is saved across restarts.
Each signed manifest points to a full package attached to the same versioned
GitHub Release. The desktop verifies the product, platform, architecture,
package type, version, release sequence, Ed25519 signature, size and SHA-256.

## Application layout

The native launcher runs `tools/chat2db-bootstrap.jar`. The bootstrap reads
`runtime/launch.json` and launches `runtime/chat2db-community.jar` with
`runtime/lib/`. Frontend assets live in `runtime/dist/`; `version.json` and
`tools/chat2db-updater.jar` remain at the app root. The packaged JBR remains
in the native platform's runtime directory.

`stage_desktop_backend.xml` assembles backend files for all three platforms;
`prepare_desktop_layout.sh` stages the frontend and release metadata. The same
layout is used for installation and full-package updates.

## Build and release

Create an annotated source tag with a positive, increasing release sequence in
its annotation, on a line in this exact format:

```text
release_epoch: 1
```

Choose a sequence greater than the last published Community release. Include
the source commit and release inputs in the annotation so the build is
reproducible. `docs/guides/community-release-tags.md` documents the tag names,
the full annotation template and how to read the published epochs before
tagging. Tag-triggered builds publish only after every platform's packages
and the Docker job succeed. Manual builds use the explicit `release_epoch`
workflow input and upload Actions artifacts without publishing a Release.

### Manual Beta workflow

Run `jcef_release.yml` from the protected `main` branch with these inputs:

| Input | Meaning |
| --- | --- |
| `version` | Application version such as `5.3.7-beta.3`, without `v`; Beta sequence 1–98 |
| `source_ref` | Reviewed Community branch, tag or commit to package |
| `release_epoch` | Explicit positive update sequence; no implicit default |

The workflow resolves `source_ref` once and all platform jobs check out that
commit. Packaging helpers and the Windows wrapper template come from the
workflow commit, so the selected source branch need not contain this workflow
or the latest packaging scripts. Source branches must contain the Community
application, updater module, and desktop resources expected by the helpers.
Only select reviewed source: its build scripts execute in a signing-enabled job.
`build-provenance.json` in each Actions artifact records both commits, the
requested ref, application/native versions, channel, and update sequence.

Application metadata, frontend version and installer filenames keep the full
version. `community-version.sh` maps it to the numeric installer version:
`major.minor.(patch * 100 + stage)`, where Beta stage is 1–98 and Stable stage
is 99. For example, `5.3.7-beta.3` becomes `5.3.703`, followed by Stable
`5.3.7` as `5.3.799`. Native major/minor must fit 0–255 and build must fit
0–65535. macOS bundle versions, Windows MSI/EXE metadata and Linux package
versions use this numeric form. Beta update manifests use channel `BETA` and
the same native version. Keep this mapping for subsequent Stable packages to
avoid a native-version downgrade after installing a Beta.

Manual Beta runs create a GitHub Pre-release with the installers and update
resources after all platform jobs pass. They do not publish Docker images or
stable/latest pointers. The release is explicitly marked prerelease and does
not become the stable Community update source. After publishing the versioned
release, the workflow appends `release-index.json` to the `community-beta-index`
branch, which is the Beta channel pointer. A published release cannot have its
assets replaced, so that branch is the only mutable part of the channel; the
versioned release itself stays immutable. The branch is machine-owned: only the
release workflow writes it, it is never merged into `main`, and it is never
reviewed. A run whose index is already on the branch makes no commit. The
workflow appends commits (no force-push) and fails when the pointer cannot be
updated. Pushing an annotated Beta tag takes the same route: it creates a
prerelease on the Beta channel, requires `release_epoch` in the tag annotation,
and never moves the stable `latest` pointer or the Docker images, so only clients that
enabled Beta updates can see it. Numeric Stable tags retain the formal release
path. Builds with `publish_release=false` do not change either update channel.

For separate source and helper checkouts, `COMMUNITY_SOURCE_DIR` points to the
application checkout; by default the packaging scripts use their own repository.

Configure these secrets in the corresponding release/test environment:

| Secret | Purpose |
| --- | --- |
| `COMMUNITY_UPDATE_KEY_ID` | Identifier of the Community Ed25519 key |
| `COMMUNITY_UPDATE_PUBLIC_KEY_B64` | Base64 DER public key bundled with the desktop |
| `COMMUNITY_UPDATE_SIGNING_PRIVATE_KEY_B64` | Base64 PEM private key used only by the manifest generator |
| `WIN_SERVER_IP`, `WIN_SERVER_USER`, `WIN_SSH_PRIVATE_KEY`, `HOST_KEY` | Existing Windows signing service connection and SSH host fingerprint |
| `REMOTE_SIGN_PATH`, `REMOTE_SIGN_SCRIPT` | Staging directory and signing script on that service |

Windows signing uploads each package to the existing remote signer, applies its
SHA-1 and SHA-256 signatures, and verifies the downloaded result before wrapping
or publishing. Configure these connection secrets in the Community release
environment.

The existing `COMMUNITY_MAC_*` secrets continue to sign and notarize macOS
packages. Use separate test keys for development builds.

The packaging script accepts `COMMUNITY_RELEASE_EPOCH`,
`COMMUNITY_UPDATE_KEY_ID`, and `COMMUNITY_UPDATE_PUBLIC_KEY_B64`. It builds the
shared updater and stages `tools/chat2db-updater.jar` plus `version.json` in
each native application. The helper is a standalone shaded artifact; the
application depends on the ordinary updater module JAR.

The desktop reads the update signing key from its launcher configuration: the
`-Dchat2db.update.key-id` and `-Dchat2db.update.public-key` java options that
`package-community-jcef.sh` derives from `COMMUNITY_UPDATE_KEY_ID` and
`COMMUNITY_UPDATE_PUBLIC_KEY_B64` and the platform scripts pass to jpackage. A
launcher configuration without those options can never verify a manifest, so
each platform script fails the build when a supplied key pair is missing from
the packaged application. No signing key is stored inside the application JARs.

## Application layout

Installation and full-package updates share one layout. A versioned-thin desktop
application is laid out as follows, and both products keep this structure with
product-specific names and values only:

| Path | Content |
| --- | --- |
| `Contents/app/<App>.cfg` (Linux/Windows: `<App>.cfg` beside the launcher) | jpackage launcher configuration, including the update signing key options |
| `app/runtime/<main jar>` | Thin launcher JAR, `chat2db-community.jar` for Community |
| `app/runtime/launch.json` | Bootstrap contract: main JAR, main class, loader path, required paths |
| `app/runtime/lib/` | All runtime dependencies |
| `app/runtime/dist/` | Frontend assets |
| `app/tools/chat2db-bootstrap.jar` | Native launcher entry point |
| `app/tools/chat2db-updater.jar` | Standalone update helper |
| `app/version.json` | `version`, `releaseEpoch`, `buildSha` |

Product differences that are expected: the application, launcher configuration
and main JAR names, the dependency set, product-specific resource files, the
product identifier, the update source URL and the signing key material. The
packaging scripts reject a stray JAR in the jpackage input root, because
jpackage copies that directory into the application and a leftover file would
ship inside the installed application.

Windows packages are signed in order: MSI, then its Inno EXE wrapper. macOS
updates contain an archive captured from the signed application in the
notarized DMG. Linux updates use DEB, RPM or AppImage according to the installed
package type.

`prepare_community_update.sh` generates a platform's signed manifests and
packages. Release aggregation requires all nine platform/package targets,
checks the payload hashes and version fields, and generates the final index.
The original nine manually downloadable installer names remain available.

## Validation

Run the updater module tests with Maven tests enabled, including
`UpdatePackagingScriptIntegrationTest`, which generates temporary Ed25519
keys and exercises all nine package targets through the Java verifier. Run
`actionlint .github/workflows/jcef_release.yml` and shell syntax checks before
publishing. Native signing, installation and startup must also be verified on
their respective operating systems.

An existing desktop without this updater must first install a version that
includes it. Test an installed version A updating to B; successfully building B
alone does not verify automatic updates. The helper records success only after
both the trial and normal application report healthy startup.

### Handoff and rollback

The application prepares the helper runtime, the helper JAR and `plan.json`, then
waits until the helper acknowledges the persisted plan before it exits; a helper
that never acknowledges fails the handoff and leaves the application running.
On macOS the helper is loaded as a per-transaction LaunchAgent
(`~/Library/LaunchAgents/com.chat2db.updater.<transaction>.plist`) with
`AbandonProcessGroup`. A helper spawned as a plain child of the application is
reclaimed together with the application, which exits right after the handoff
while the helper JVM is still starting, and `AbandonProcessGroup` keeps the
relaunched application alive once the helper exits.

Before the switch the installed package is moved aside to
`<install target>.chat2db-previous` on the same volume, so the switch no longer
deletes the only working copy. The transaction commits only after the trial and
the relaunched application both report healthy startup, and that commit releases
the backup. Any failure after the switch restores and relaunches the previous
package and records `stage=ROLLING_BACK` in the product update log. The helper
also refuses to switch while another instance of the installed application is
still running, because such an instance makes the trial candidate exit
immediately. A transaction that fails without a rollback copy leaves the update
log as the only record; installation and startup failures are appended to it.

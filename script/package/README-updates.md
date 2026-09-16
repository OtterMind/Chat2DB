# Desktop update packages

Community desktop checks the stable index at
`https://github.com/OtterMind/Chat2DB/releases/latest/download/release-index.json`.
When **Receive Beta versions** is enabled, it also checks
`https://github.com/OtterMind/Chat2DB/releases/download/community-beta/release-index.json`
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
reproducible. Tag-triggered builds publish only after every platform's packages
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
release, the workflow updates `release-index.json` on the `community-beta`
prerelease. This channel release holds only the index; its manifests and
packages continue to point to immutable versioned releases. Publication is
serialized and rejects an older or conflicting release sequence.
Beta-tag pushes are rejected before signing/publication. Numeric Stable tags
retain the formal release path. Builds with `publish_release=false` do not
change either update channel.

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
both the trial and normal application report healthy startup. Installation or
startup failures are recorded in the product update log; there is no automatic
rollback.

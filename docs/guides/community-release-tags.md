# Community release tags

This guide defines how Community release tags are named and annotated. The
annotation is written by hand and is the release contract: the release workflow
reads it and validates only what it can check locally, so the values that
depend on what is already published stay a human responsibility.

## Release paths

| Path | How it starts | Update channel | GitHub Release |
| --- | --- | --- | --- |
| Stable | Push an annotated `v<version>` tag | `STABLE` | Published and marked as the repository `latest` release |
| Beta | Push an annotated `v<version>-beta.<n>` tag, or run *Build Community Desktop Release* from `main` | `BETA` | A prerelease with `latest=false`; the manual run needs `publish_release=true` |

A tag push creates the release itself: a numeric tag takes the Stable channel
and the repository `latest` pointer, a `-beta.N` tag resolves to `channel=beta`,
`prerelease=true`, `latest=false` and `publish=false`, so it creates a
prerelease, leaves the stable index and the Docker images untouched, and only
reaches clients that enabled Beta updates. Both tag routes require the annotated
`release_epoch:` line. The manual run is the alternative for artwork-only
builds: it requires `-beta.N` (`Manual Beta builds require a version ending in
-beta.N.`) and the protected `main` branch, and publishes only with
`publish_release=true`.

## Tag names

- Stable: `v<major>.<minor>.<patch>`, for example `v5.3.7`. The major version
  must be at least 4; older numbers are rejected.
- Beta: `v<major>.<minor>.<patch>-beta.<n>`, for example `v5.3.7-beta.3`. Push it
  to publish the Beta prerelease and to update the Beta channel index. The manual
  run builds the same version from `source_ref`, which must name a branch, tag or
  commit that exists on the remote, so reference `main` or a reviewed commit.
  When a manual run creates the release itself, it creates this tag on the built
  commit (`gh release create` without `--verify-tag`), and that API-created tag
  does not start another workflow run.
- Beta build record: `<beta tag>-build.<n>`, for example
  `v5.3.7-beta.3-build.3`. It records which workflow commit produced a build and
  is created outside the workflow.
- A tag push always has its tag, so the release is created from it. A manual run
  without an existing tag relies on `--target` instead.

## Required annotation

The workflow accepts only annotated tags: a lightweight tag fails with
`git cat-file -t` reporting `commit` instead of `tag`. A tag-triggered run must
also contain one line of its own in the annotation:

```text
release_epoch: <positive integer>
```

The workflow extracts that line from `git for-each-ref refs/tags/<tag>
--format='%(contents)'` and requires a positive integer. A missing, empty or
non-numeric value fails the run with `A positive release_epoch is required in the
annotated release tag or test inputs.` before any package is built. Manual Beta
runs pass the same value as the `release_epoch` workflow input instead.

## Stable annotation template

```text
Chat2DB Community 5.3.7

Source repository: https://github.com/OtterMind/Chat2DB
Source commit: <40-character commit sha>
Community ref: <40-character commit sha>
Packaging repository: https://github.com/OtterMind/Chat2DB
Workflow ref: refs/tags/v5.3.7 (<40-character commit sha>)
Product workflow: .github/workflows/jcef_release.yml
Product: Community
Resolved version: 5.3.7
Resolved channel: release
Desktop targets: macos-arm64, macos-x64, windows, linux-x64, linux-arm64

Previous release: v5.3.6
Observed online epochs before release:
  Community: stable=<published stable epoch>, beta=<published beta epoch>

release_epoch: <greater than every observed epoch>
```

## Beta annotation template

```text
Community Beta build 5.3.7-beta.3 build.3
source_repository: OtterMind/Chat2DB
source_commit: <40-character commit sha>
community_ref: <40-character commit sha>
workflow_repository: OtterMind/Chat2DB
workflow_ref: main
workflow_commit: <40-character commit sha>
workflow: .github/workflows/jcef_release.yml
version: 5.3.7-beta.3
source_ref: <40-character commit sha>
release_epoch: <greater than every observed epoch>
publish_release: false
distribution: GitHub Actions artifacts
```

## Choosing the epoch

Nothing in the pipeline compares the new epoch with what is already published, so
read the published indexes before tagging and record what you saw in the
annotation:

```bash
curl -fsSL https://github.com/OtterMind/Chat2DB/releases/latest/download/release-index.json | jq .releaseEpoch
curl -fsSL https://raw.githubusercontent.com/OtterMind/Chat2DB/community-beta-index/release-index.json | jq .releaseEpoch
```

Pick a value greater than every published epoch and greater than the epoch of the
last release of the same channel. Before a channel has published its first index
the command above reports a missing asset, so start that channel at `1`. The
desktop ignores an index whose `releaseEpoch` is not greater than the installed
one, and every manifest must also advance the installed version and epoch, so a
release that reuses an epoch looks like "no update" to every client that is
already on it.

Re-publishing an older stable tag is also a downgrade of the update source:
publishing marks the tag as `latest`, which moves both
`releases/latest/download/release-index.json` and the download entry point back to
the older release. Clients on a newer epoch skip that index, but nothing else
prevents the move, so publish each stable tag once.

The Stable channel pointer is GitHub's own `latest` marker, so every Stable
release carries its index already. The Beta channel needs a pointer that changes
on every release, which a published release cannot provide, so the Beta index
lives on the machine-owned `community-beta-index` branch and is appended by the
release workflow. This has consequences for anyone touching tags or releases:

- **Never delete a release or a tag.** A published release is immutable, and
  GitHub keeps its tag name reserved afterwards, so the name can never be used
  again. One Community Beta channel was lost this way; the reserved name stays
  unusable even after release immutability is disabled.
- **The workflow token cannot create tags.** The `release-tags-owner-only`
  ruleset restricts `refs/tags/**` creation, updates, and deletions to one team.
  A release must therefore be created on a tag that already exists.
- **The Beta index is published after the versioned release.** The workflow
  appends the index to `community-beta-index`; the branch is never merged into
  `main` and never reviewed.
- **Stable `release_epoch` must exceed every published epoch.** Clients ignore an
  index whose epoch is not greater than the installed one, so a Stable release
  that reuses an epoch is invisible to desktops already on it. After the first
  Beta shipped with epoch `1`, the next Stable tag needs `release_epoch: 2` or
  higher.

## What the workflow checks

- The tag name starts with `v` and the major version is at least 4.
- The tag is annotated and carries a positive `release_epoch:`.
- A tag push derives its channel from the version: numeric tags publish a stable,
  `latest` release on the Stable channel, `-beta.N` tags publish a prerelease on
  the Beta channel and update the Beta index.
- A manual run starts from `main`, needs an explicit `source_ref` and a
  `-beta.N` version, and never updates the stable `latest` pointer.
- Every release carries exactly nine manifests (macOS arm64/x64, Windows x64,
  Linux arm64/x64 appimage/deb/rpm); the workflow re-checks each package size and
  SHA-256 against its manifest, and the Beta publisher validates the channel index
  before it replaces the published one.

## Related documentation

- `script/package/README-updates.md` describes packaging, signing secrets and the
  update channel layout.
- `docs/guides/community-jcef-development.md` describes running the desktop shell
  from a checkout.

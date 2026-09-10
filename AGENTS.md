# Chat2DB Community Agent Contract

## Context

This file applies to the entire repository. Chat2DB Community is a Java 17,
Spring Boot, Maven, React, TypeScript, and Umi application distributed as a web
application, Docker image, and JCEF desktop package.

The current repository is the source of truth for current behavior. A user
request can define a desired behavior change, but it does not turn a historical
assumption into fact or grant permission for an unrelated external action.

Instruction authority and factual evidence are separate:

- Follow applicable higher-level instructions, then the nearest scoped
  `AGENTS.md`, then the latest user request. At the same authority level, the
  newer explicit instruction wins.
- For current checkout behavior, prefer current source and tests, then
  repository-local workflows and scripts, and then documentation. For current
  runtime state, live evidence wins only after the process `cwd`, artifact,
  branch/ref, or equivalent provenance ties it to the intended checkout.
  Unproven processes from another checkout do not override source facts.
  Prior memory or subagent reports rank below inspected evidence; inference is
  last.
- Treat prior memory and subagent output as potentially stale leads. Verify
  consequential claims against this checkout and never let memory authorize a
  mutation or external action.
- Do not write durable memory about the repository unless the user explicitly
  asks for it. Never carry secrets or user data into memory.

### Product invariants

- Backend Community behavior is selected by
  `-Dchat2db.runtime.mode=community`.
- Frontend Community behavior is selected by `UMI_ENV=community`.
- `-Dchat2db.finalName=chat2db-community` changes the artifact name only; it is
  not a runtime-mode signal.
- Community desktop/package scripts and the documented local backend command
  bind `127.0.0.1:10825`. The Community frontend dev server uses port `8889`
  and proxies to that backend. `application-dev.yml` sets only port `10825`, so
  local launchers must pass the address when loopback-only behavior is required.
- The Docker container intentionally listens on `0.0.0.0:10825` internally;
  Compose limits the default host publication to `127.0.0.1:10825`.
- Packaged Community desktop runtime is offline-first and local: keep
  `-Dchat2db.network.status=OFFLINE`, loopback binding, Community identity, and
  Community storage paths intact unless the task explicitly changes the
  product contract.
- Do not reintroduce Enterprise Gateway forwarding, cloud account,
  subscription, payment, commercial license, Local-edition packaging, or
  Electron runtime paths into Community code.
- Community desktop supports in-app updates. Keep the renderer update UI and
  state, JCEF update handlers, updater metadata, packaged payloads, and Windows
  elevated updater helper aligned when changing the release format.
- Do not treat hidden frontend UI as backend isolation. Remote calls, headers,
  cookies, controllers, and service registration must also be Community-safe.
- Preserve public API, storage, datasource, namespace, console, task, pin,
  operation-log, and ER-position semantics unless an explicit compatibility or
  migration change is requested.

### Repository map

- `chat2db-community-client/`: Umi/React frontend and JCEF renderer bridge.
- `chat2db-community-server/`: Maven reactor containing domain, storage, web,
  JCEF, SPI, plugin, tools, and startup modules.
- `chat2db-community-server/chat2db-community-start/`: executable backend,
  configuration, assembled frontend resources, and final jar.
- `chat2db-community-server/chat2db-community-agent/`: agent runtime adapters,
  installation, process/RPC transport, and runtime resources. It depends only
  on tools and third-party libraries; start assembles it with business services.
- `chat2db-community-server/chat2db-community-plugins/`: database-specific
  implementations. Keep dialect-specific behavior inside the owning plugin.
- `docker/`: Community image, Compose file, and local image build entry point.
- `script/package/`: Community desktop preparation, metadata, and native
  packaging entry points.
- `jpackage/`: only versioned native packaging resources belong in source;
  runtime images, staged jars/libraries, renderer output, and installers are
  generated.
- `.github/workflows/`: Community desktop artifact and Docker publication
  workflows.
- `spec/code/server/`: maintained Java boundary and contract documentation.
  These Markdown files are rules, not executable checks.

### Structural code navigation

Use CodeGraph for structural questions when `.codegraph/` is initialized and
the CodeGraph tools are available:

- `codegraph_context` first for a feature, architecture, or bug path.
- `codegraph_explore` once for the related source surfaced by context.
- `codegraph_search` for symbol lookup, `codegraph_callers` and
  `codegraph_callees` for call paths, and `codegraph_impact` before shared
  symbol changes.
- `codegraph_files` for indexed source-tree exploration and
  `codegraph_status` for index health.

Use `rg` for literal strings, configuration keys, logs, comments, generated
flags, and command lines. Do not repeat CodeGraph results with a grep/read loop.
The index can lag writes, and build tools remain the correctness authority. If
CodeGraph is unavailable, use focused native file reads and `rg` without
pretending indexed results exist. If the repository has no initialized
CodeGraph index, ask before creating one.

## Request

Classify the request before acting:

- Answer, explain, review, diagnose, and status requests are read-only by
  default. Use non-mutating inspection and report evidence; do not edit files,
  run mutation-heavy builds, or implement a fix unless requested.
- Change, fix, and cleanup requests authorize local source edits and relevant
  local commands within the named scope. A build request authorizes build
  commands, dependency caches, and generated output only; it does not authorize
  source repair unless the user also asks for a fix.
- Commit, push, tag, workflow dispatch, publication, deployment, signing, and
  production or customer-data actions require explicit authorization for that
  external effect.

For an authorized implementation task, deliver the requested outcome end to
end within the smallest coherent scope.

1. Confirm the exact repository, branch, status, and existing user changes.
2. Read the nearest implementation, tests, configuration, and workflow before
   editing. Trace cross-layer behavior when a change spans frontend, backend,
   storage, Docker, or desktop packaging.
3. Classify the change by affected surface and choose verification from the
   matrix below.
4. Edit narrowly. Follow existing module ownership and local conventions.
5. Run the relevant checks, inspect the final diff, and distinguish executed
   verification from checks that were not available.
6. Report the result, remaining risk, and exact uncommitted or unpushed state.

### Backend boundaries

- Keep controllers and transport adapters thin. Business behavior belongs in
  domain services; persistence belongs behind storage APIs/providers.
- Preserve Maven module direction and the contracts documented under
  `spec/code/server/`. Do not bypass domain APIs with new web-to-storage
  coupling.
- Use Java 17 and existing Spring/MyBatis/Lombok patterns. Avoid introducing a
  parallel framework or utility abstraction for behavior already owned by a
  module.
- New Community runtime branches must use the real runtime-mode signal. Never
  infer mode from jar name, app name, port, or artifact path.
- Changes to shared request context, identity, Gateway isolation, storage
  routing, AI services, or JCEF startup require explicit call-path and
  regression review.
- Keep database-specific SQL, metadata, completion, and type behavior in its
  plugin. Add focused plugin tests for dialect changes.

### Frontend boundaries

- Use TypeScript, React, Umi, Zustand, Ant Design, and existing project
  components and service helpers.
- Community conditions must derive from the current runtime-edition helpers or
  `UMI_ENV=community`; do not scatter unrelated environment checks.
- Do not add Electron APIs, `ipcRenderer`, Electron packaging, or a duplicate
  desktop bridge. Desktop renderer communication uses the existing JCEF
  `window.javaQuery` wrappers.
- Preserve service response contracts and storage keys. UI removal does not
  authorize deleting a backend contract that another Community path uses.
- Keep user-facing strings in the existing i18n system when the surrounding
  feature is localized.

### Configuration and generated files

- Never commit credentials, tokens, signing material, private URLs, local
  database contents, IDE files, runtime downloads, Maven/Yarn output, frontend
  `dist`, staged `jpackage` content, or installers.
- Read secrets only when the explicitly authorized operation requires them.
  Never print, log, paste, summarize, or persist secret values. Do not reuse a
  credential for a different repository, registry, account, or environment.
- Do not access or mutate production data, user data, or an unrelated local
  database for build or diagnostic convenience. Use fixtures or a named local
  test datasource unless the user explicitly places another system in scope.
- Keep desktop and host-published listeners on loopback unless remote exposure
  is explicitly requested and documented with its security impact. Do not
  change the container's required internal `0.0.0.0` binding to enforce a host
  publication policy.
- Keep these six `jpackage/input` resources versioned:
  `icons/community/logo.icns`, `icons/community/logo.ico`,
  `icons/community/logo.png`, `macres/Info.plist`, `win/updater.jar`, and
  `win/run-as-admin.vbs`.
- Treat `target/`, frontend `dist/`, `jpackage/output/`,
  `jpackage/input/runtime/`, and generated platform content such as
  `chat2db-community.jar`, `dist/`, `lib/`, and macOS `Frameworks/` as
  reproducible output. Do not
  remove a tracked resource merely because it shares a parent directory with
  generated files.
- Update README and workflow examples when a public command, port, artifact,
  environment variable, or release input changes.

## Output Format

At handoff, state:

- what behavior changed and which files own it;
- which commands were actually run and whether they passed;
- any verification that remains CI-only, platform-only, or otherwise unrun;
- the current Git state, including unrelated modifications left untouched; and
- every attempted external action and its commit, remote branch, workflow run,
  image, release, or artifact identifier, including partial or failed actions.

For code review, report findings first in severity order with `file:line`, the
affected runtime surface, the behavioral risk, and the required fix or missing
evidence. If there are no findings, say so and list residual test gaps.

Do not claim that a build ran tests when Maven was invoked with
`-Dmaven.test.skip=true`. Do not claim a native package works from shell syntax
or `prepare` alone.

## Constraints

### Baseline commands

Use Java 17, Maven 3.8 or later, Node.js 18 or later, and Yarn with the checked-in
`chat2db-community-client/yarn.lock`.

Frontend setup and verification:

```bash
cd chat2db-community-client
yarn install --frozen-lockfile
yarn run lint
yarn run test:sql-in-clipboard
yarn run build:web:community --app_version=0.0.0
```

Run a focused backend test with tests explicitly enabled because the parent BOM
defaults `maven.test.skip` to `true`. The reactor POM also has a stale Surefire
include and `testFailureIgnore=true`; override both. Replace the example module
and test with the owning artifact ID and test class:

```bash
MODULE=:chat2db-community-spi
TEST=DefaultSqlBuilderSegmentTest
mvn -B -f chat2db-community-server/pom.xml \
  -pl "${MODULE}" -am \
  -Dmaven.test.skip=false -DskipTests=false \
  -Dtest="${TEST}" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore=false \
  test
```

For the owning module's full test set, replace `-Dtest` with the quoted argument
`'-Dsurefire.includes=**/*Test.java'`. This can surface stale test-compilation
failures elsewhere in that module; report those instead of weakening test
discovery. Always confirm the Surefire summaries report a nonzero test count
with zero failures and errors; `BUILD SUCCESS` alone is not sufficient in this
reactor.

Build the executable Community backend:

```bash
mvn -B clean package \
  -Dmaven.test.skip=true \
  -Dchat2db.finalName=chat2db-community \
  -f chat2db-community-server/pom.xml \
  -pl chat2db-community-start -am
```

Run it locally with the explicit Community contract:

```bash
java -Dloader.path=chat2db-community-server/chat2db-community-start/target/lib \
  -Dchat2db.gui=false \
  -Dchat2db.runtime.mode=community \
  -Dchat2db.network.status=OFFLINE \
  -Dserver.address=127.0.0.1 \
  -Dserver.port=10825 \
  -Dspring.profiles.active=dev \
  -jar chat2db-community-server/chat2db-community-start/target/chat2db-community.jar
```

Build local distribution surfaces only when relevant:

```bash
./docker/docker-build.sh 0.0.0-local chat2db-community:local
docker compose -f docker/docker-compose.yml config
script/package/package-community-jcef.sh 0.0.0-local prepare
```

The desktop script's `mac`, `linux`, and `win` targets are platform-specific and
may download a JBR. Signed macOS packages require CI secrets. A manual desktop
workflow dispatch only uploads GitHub Actions artifacts. Pushing a numeric
`v5.3.0`-style tag validates all nine native installers, adds `SHA256SUMS`,
stages a draft GitHub Release, calls the reusable Docker workflow for the
versioned and `latest` tags, and publishes the Release only after Docker
succeeds. Neither path uploads to the updater CDN. These are external
publication actions, not routine local verification commands.

### Tool and environment boundaries

- For an authorized change/build task, repository edits, generated build
  output, standard Maven/Yarn caches, and ordinary dependency downloads are
  allowed. Do not change global package-manager configuration or install system
  packages unless the task requires it and the impact is explicit.
- Starting a local service is allowed only when needed for the requested smoke
  test. Inspect the port first, bind to loopback, record the process, and stop
  only the process started for this task. Never bounce an unrelated service.
- Docker build and task-scoped test containers/images are allowed for a Docker
  build request. Do not prune the daemon, remove unrelated resources, or start
  Compose services that are outside the task.
- Use only named local/test databases for autonomous verification. Schema/data
  mutation in shared, staging, or production systems requires explicit scope,
  a recovery plan, and authorization.
- Read-only network access for dependency resolution and public source checks
  is allowed when needed. Authenticated writes, uploads, workflow dispatches,
  messages, and publication remain external actions requiring explicit user
  authority.
- If a required tool is unavailable, report the missing check or use an
  equivalent read-only fallback. Do not fabricate tool output or silently
  broaden the environment.

### Verification matrix

- Frontend logic, route, service, or state: relevant focused test, `yarn run
  lint`, and `build:web:community`.
- Backend domain, web, storage, SPI, or plugin code: focused tests with Maven
  tests explicitly enabled, the stale Surefire include and failure-ignore
  behavior overridden, and a confirmed nonzero test count; then package the
  affected module and dependencies.
- Shared API or persistence contract: add compatibility coverage and inspect
  callers plus serialization/storage effects.
- Runtime-mode, identity, Gateway, AI, or network isolation: inspect both the
  registration path and call path; run a Community-mode smoke when feasible.
- JCEF Java bridge: focused JCEF tests, reactor compile/package, and matching
  renderer bridge verification.
- Desktop scripts/resources: `bash -n` for touched shell scripts, plist or
  platform metadata validation, `prepare`, and cross-platform workflow review.
  Only claim native installer success for an installer actually built and run
  on its target platform.
- Docker: script syntax, Compose expansion, image build, and an HTTP/static
  asset smoke when Docker is available.
- Workflow YAML: parse or inspect the resolved workflow inputs and commands;
  distinguish artifact upload from GitHub Release publication and CDN upload.
- Documentation-only changes: verify commands, links, paths, ports, and names
  against current source and workflows.
- Every change: `git diff --check`, focused diff review, and final `git status`.

### Git and external-action rules

- Preserve unrelated user changes, including changes in files you also need to
  edit. Never reset, checkout, clean, or overwrite them to simplify the task.
- Do not use destructive Git commands. Do not remove local data or generated
  caches outside the requested scope.
- Do not stage, commit, push, tag, dispatch workflows, publish releases, upload
  packages, or push images unless the user explicitly requests that action.
- When commit or push is requested, stage only the intended semantic scope,
  inspect the staged diff, check it for credentials or generated output, run
  its checks, and verify local/remote commit IDs.
- Treat workflow dispatch, release publication, Docker push, CDN upload,
  `latest` updates, and signing as external mutations with an explicit approval
  boundary.

## Checkpoint

Continue autonomously for normal read, edit, build, and test work within the
requested scope. Stop and ask only when one of these conditions materially
changes the result:

- applicable instructions conflict, or the desired behavior remains ambiguous
  after current source has established the existing behavior;
- the task requires deleting user data, changing a public compatibility
  contract, exposing a listener, or crossing into Enterprise/Local behavior;
- credentials, signing identity, release destination, or production authority
  are missing;
- an irreversible or externally visible action was not explicitly requested;
- unrelated user edits make the requested change unsafe to isolate; or
- required verification still fails after one diagnosis-and-repair cycle and
  no safe in-scope recovery remains.

Retry a transient, idempotent operation at most twice. Never blindly repeat a
push, workflow dispatch, release, upload, signing request, or other external
mutation after an ambiguous result. Inspect the remote or provider state first.
After interruption or partial completion, recheck branch, worktree, remote, and
workflow state before resuming.

On failure, keep the worktree recoverable, record the exact command and error,
avoid unsupported success claims, and hand off the current goal, evidence,
actions already attempted, identifiers, open risks, and next safe action.

# Community Web frontend with JCEF backend

This guide is for contributors who can already build and run the Community Web
frontend and backend, and need to test the same checkout in the JCEF Desktop
shell.

The launcher does not prepare the development environment. It does not install
dependencies, build the backend, or discover Java runtimes. It only refuses to
start when either required port is already occupied.

## Prerequisites

Before starting JCEF Desktop:

1. Install the project dependencies and build the Community backend by following
   the main README.
2. Download the JBR with JCEF described below and set `JBR_HOME` to it.
3. Ensure ports `8889` and `10825` are free on all local interfaces. Umi checks
   every local interface before selecting its development port.
4. Stop the Web backend, because JCEF Desktop starts its embedded backend on
   `127.0.0.1:10825`.

The launcher expects the previously built backend at:

```text
chat2db-community-server/chat2db-community-start/target/chat2db-community.jar
chat2db-community-server/chat2db-community-start/target/lib/
```

## Download JBR with JCEF

JCEF requires JetBrains Runtime with JCEF; a standard OpenJDK installation is
not sufficient. Use the same JBR release as the Community packaging script:
[JBR 17.0.12, build 1207.37][jbr-release].

| Platform | Download |
| --- | --- |
| macOS Apple Silicon | [jbr_jcef-17.0.12-osx-aarch64-b1207.37.tar.gz][jbr-macos-arm64] |
| macOS Intel | [jbr_jcef-17.0.12-osx-x64-b1207.37.tar.gz][jbr-macos-x64] |
| Linux ARM64 | [jbr_jcef-17.0.12-linux-aarch64-b1207.37.tar.gz][jbr-linux-arm64] |
| Linux x64 | [jbr_jcef-17.0.12-linux-x64-b1207.37.tar.gz][jbr-linux-x64] |
| Windows x64 | [jbr_jcef-17.0.12-windows-x64-b1207.37.tar.gz][jbr-windows-x64] |

After extracting the archive, set `JBR_HOME` to the directory containing
`bin/java` (`bin/java.exe` on Windows). On macOS this directory normally ends
with `Contents/Home`. Verify the runtime before starting Chat2DB:

```bash
export JBR_HOME=/path/to/extracted-jbr/Contents/Home
"$JBR_HOME/bin/java" -version
```

The version output should identify JetBrains Runtime and include `jcef`.

## Start the Web frontend and JCEF backend

From the repository root, run:

```bash
JBR_HOME=/path/to/jbr ./script/dev-community-jcef.sh
```

To exercise update discovery without depending on a published GitHub Release,
point the development launcher at a local updater artifact directory containing
`latest_version.json`, `version.json`, and any payload files referenced by the
manifest:

```bash
CHAT2DB_DEV_UPDATE_DIRECTORY=/absolute/path/to/update-fixture \
  JBR_HOME=/path/to/jbr \
  ./script/dev-community-jcef.sh
```

The override is accepted only in Community Desktop development mode. Release,
non-Community, and non-Desktop runtimes ignore it and continue to use the fixed
GitHub Release source. Development builds can check and display the fixture but
still do not enable automatic installation.

The script starts the Community Web frontend with
`yarn run start:community:hot`, binds it to `127.0.0.1:8889`, waits up to 180
seconds for Umi to compile and serve `umi.js`, and then starts the JCEF backend
with `-Dchat2db.jcef.web-frontend=true`. That parameter tells JCEF to load the
Web frontend instead of packaged frontend files. The script does not start a
separate Web backend. It waits up to another 120 seconds for the embedded
backend's `/api/system` health check to succeed on `127.0.0.1:10825`.
On macOS, the launcher also starts Java on the AppKit first thread as required
by AWT and JCEF.

Press `Ctrl+C` to stop both processes. If either child exits, the launcher stops
the other one. It prints the checkout, JBR, backend jar, child PIDs, listener
URLs, readiness result, and attached-log location. Missing dependencies, build
artifacts, and runtime files are still reported directly by Yarn, curl, or Java.

Without `-Dchat2db.jcef.web-frontend=true`, JCEF continues to load the bundled
`dist/index.html`. Packaged releases do not pass this parameter and are
unchanged.

[jbr-release]: https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.12b1207.37
[jbr-macos-arm64]: https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-17.0.12-osx-aarch64-b1207.37.tar.gz
[jbr-macos-x64]: https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-17.0.12-osx-x64-b1207.37.tar.gz
[jbr-linux-arm64]: https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-17.0.12-linux-aarch64-b1207.37.tar.gz
[jbr-linux-x64]: https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-17.0.12-linux-x64-b1207.37.tar.gz
[jbr-windows-x64]: https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-17.0.12-windows-x64-b1207.37.tar.gz

/**
 * electron-builder `beforePack` hook — Cutting Edge (CE)
 *
 * The CI job builds the backend into <repo>/build/backend using a *virtualenv*.
 * A virtualenv is NOT portable: its python.exe resolves the standard library
 * through pyvenv.cfg -> the build machine's Python installation, which does not
 * exist on the end user's PC.
 *
 * This hook converts that layout into a fully self-contained runtime:
 *   1. download the official embeddable CPython 3.11 distribution
 *   2. enable `site` so that Lib\site-packages is importable
 *   3. move the site-packages produced by CI into the embeddable runtime
 *   4. replace build/backend/python with the portable runtime
 *
 * It is a no-op on non-Windows hosts or when the runtime is already portable.
 */
const fs = require('fs')
const path = require('path')
const https = require('https')
const { execFileSync } = require('child_process')

const PY_EMBED_URL =
  'https://www.python.org/ftp/python/3.11.9/python-3.11.9-embed-amd64.zip'
// The *full* build, the same one CI downloads. The `essentials` archive was here
// first and quietly shipped a weaker FFmpeg (fewer filters) whenever the
// installer was built outside CI — a capability difference nobody would notice
// until a filter was missing on a user's machine.
const FFMPEG_ZIP_URL = 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-full.7z'
const GET_PIP_URL = 'https://bootstrap.pypa.io/get-pip.py'

function log(msg) {
  console.log(`  [ce:before-pack] ${msg}`)
}

function download(url, dest) {
  return new Promise((resolve, reject) => {
    const request = (u, redirects = 0) => {
      https
        .get(u, (res) => {
          if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
            if (redirects > 5) return reject(new Error('too many redirects'))
            res.resume()
            return request(res.headers.location, redirects + 1)
          }
          if (res.statusCode !== 200) {
            res.resume()
            return reject(new Error(`HTTP ${res.statusCode} for ${u}`))
          }
          const file = fs.createWriteStream(dest)
          res.pipe(file)
          file.on('finish', () => file.close(() => resolve(dest)))
          file.on('error', reject)
        })
        .on('error', reject)
    }
    request(url)
  })
}

function unzip(zipPath, destDir) {
  // The full FFmpeg build is only published as .7z, which Expand-Archive cannot
  // read. 7-Zip is on the GitHub Windows runner and on most dev machines; if it
  // is missing we say so instead of silently shipping a weaker build.
  if (/\.7z$/i.test(zipPath)) {
    try {
      execFileSync('7z', ['x', zipPath, `-o${destDir}`, '-y'], { stdio: 'inherit' })
      return
    } catch {
      throw new Error(
        `7-Zip is needed to unpack ${path.basename(zipPath)} (the full FFmpeg build). ` +
          'Install it (winget install 7zip.7zip) and build again.'
      )
    }
  }
  execFileSync(
    'powershell',
    [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      `Expand-Archive -LiteralPath '${zipPath}' -DestinationPath '${destDir}' -Force`,
    ],
    { stdio: 'inherit' }
  )
}

function findSitePackages(root) {
  const candidates = [
    path.join(root, 'Lib', 'site-packages'),
    path.join(root, 'lib', 'site-packages'),
  ]
  for (const c of candidates) if (fs.existsSync(c)) return c
  return null
}

/** The CI job only copies ffmpeg.exe; core/engine/ingest.py also needs ffprobe.exe. */
async function ensureFfprobe(ffmpegDir) {
  if (!fs.existsSync(ffmpegDir)) {
    fs.mkdirSync(ffmpegDir, { recursive: true })
  }
  const needed = ['ffmpeg.exe', 'ffprobe.exe'].filter(
    (exe) => !fs.existsSync(path.join(ffmpegDir, exe))
  )
  if (needed.length === 0) {
    log('ffmpeg + ffprobe already bundled')
    return
  }
  log(`missing ${needed.join(', ')} — fetching official FFmpeg build`)
  const zipPath = path.join(ffmpegDir, path.basename(new URL(FFMPEG_ZIP_URL).pathname))
  const work = path.join(ffmpegDir, '_extract')
  fs.rmSync(work, { recursive: true, force: true })
  await download(FFMPEG_ZIP_URL, zipPath)
  unzip(zipPath, work)
  fs.rmSync(zipPath, { force: true })
  const stack = [work]
  const found = {}
  while (stack.length) {
    const dir = stack.pop()
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) stack.push(full)
      else if (needed.includes(entry.name) && !found[entry.name]) found[entry.name] = full
    }
  }
  for (const exe of needed) {
    if (!found[exe]) throw new Error(`${exe} not found in the FFmpeg archive`)
    fs.copyFileSync(found[exe], path.join(ffmpegDir, exe))
    log(`bundled ${exe}`)
  }
  fs.rmSync(work, { recursive: true, force: true })
}

/**
 * Differential updates only pay off when unchanged files are byte-identical
 * between releases, so timestamps are pinned to a fixed epoch.
 *
 * Bytecode used to be *deleted* for the same reason, and that was a real cost
 * paid by the user: with no `.pyc` anywhere, starting the backend measured
 * **1.16 s** against **0.72 s** with bytecode present. It is not needed —
 * `compileall` with `unchecked-hash` invalidation writes caches that carry the
 * source hash instead of an mtime, so they are byte-identical between builds
 * *and* they are used. We ship the bytecode and keep the determinism.
 */
const DETERMINISTIC_MTIME = new Date('2020-01-01T00:00:00Z')

function normalizeForDelta(root) {
  if (!fs.existsSync(root)) return { removed: 0, touched: 0 }
  let removed = 0
  let touched = 0
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        walk(full)
        try { fs.utimesSync(full, DETERMINISTIC_MTIME, DETERMINISTIC_MTIME) } catch { /* ignore */ }
        touched++
      } else {
        try { fs.utimesSync(full, DETERMINISTIC_MTIME, DETERMINISTIC_MTIME) } catch { /* ignore */ }
        touched++
      }
    }
  }
  walk(root)
  return { removed, touched }
}

module.exports = async function beforePack(context) {
  if (process.platform !== 'win32') {
    log('not running on Windows — skipping backend runtime conversion')
    return
  }

  const frontendDir = path.resolve(__dirname, '..')
  const buildRoot = path.resolve(frontendDir, '..', '..', 'build')
  const backendDir = path.join(buildRoot, 'backend')
  const pythonDir = path.join(backendDir, 'python')

  await ensureFfprobe(path.join(buildRoot, 'ffmpeg'))

  if (!fs.existsSync(pythonDir)) {
    log(`no backend runtime at ${pythonDir} — nothing to do`)
    return
  }

  // Already portable (embeddable distributions ship a python3xx._pth file).
  const alreadyPortable = fs
    .readdirSync(pythonDir)
    .some((f) => /^python\d+\._pth$/i.test(f))
  if (alreadyPortable) {
    log('backend runtime is already an embeddable distribution — skipping')
    return
  }

  const sitePackages = findSitePackages(pythonDir)

  log('converting virtualenv backend runtime into a portable one…')
  const work = path.join(backendDir, '_python_embed')
  fs.rmSync(work, { recursive: true, force: true })
  fs.mkdirSync(work, { recursive: true })

  const zipPath = path.join(backendDir, 'python-embed.zip')
  log(`downloading ${PY_EMBED_URL}`)
  await download(PY_EMBED_URL, zipPath)
  unzip(zipPath, work)
  fs.rmSync(zipPath, { force: true })

  // Enable site-packages inside the embeddable runtime.
  const pth = fs.readdirSync(work).find((f) => /^python\d+\._pth$/i.test(f))
  if (!pth) throw new Error('embeddable python: _pth file missing')
  const stdlibZip = pth.replace('._pth', '.zip')
  fs.writeFileSync(
    path.join(work, pth),
    [stdlibZip, '.', 'Lib\\site-packages', 'import site', ''].join('\r\n')
  )

  // Move whatever the CI step managed to install into the portable runtime.
  const target = path.join(work, 'Lib', 'site-packages')
  fs.mkdirSync(path.dirname(target), { recursive: true })
  if (sitePackages && fs.readdirSync(sitePackages).length > 0) {
    log(`moving site-packages -> ${target}`)
    fs.renameSync(sitePackages, target)
  } else {
    log('CI virtualenv has no packages — the runtime will be populated from requirements.txt')
    fs.mkdirSync(target, { recursive: true })
  }

  // Drop build-time only artefacts to keep the installer smaller.
  for (const entry of fs.readdirSync(target)) {
    if (entry === 'pip' || entry.startsWith('pip-')) {
      fs.rmSync(path.join(target, entry), { recursive: true, force: true })
    }
  }

  fs.rmSync(pythonDir, { recursive: true, force: true })
  fs.renameSync(work, pythonDir)

  const exe = path.join(pythonDir, 'python.exe')

  // The CI step that populates the virtualenv ignores pip failures, so the
  // runtime may be missing dependencies. Detect that and install them here —
  // the installer must never ship a backend that cannot start.
  if (!canImport(exe, backendDir)) {
    log(`the runtime cannot start the app yet:\n${lastImportError}`)
    log('installing the dependencies now')
    installRequirements(exe, backendDir, frontendDir)
  }

  if (!canImport(exe, backendDir)) {
    // Say *why*. The first version threw a sentence with no cause in it, and the
    // build log gave the next person nothing to work with.
    throw new Error(
      `portable backend runtime is still incomplete — aborting build\n${lastImportError}`
    )
  }
  log('portable backend runtime ready')

  // Bytecode first, then timestamps: the .pyc files have to be pinned too.
  precompile(exe, backendDir)

  // Make the shipped payload reproducible so update patches stay small.
  const backendNorm = normalizeForDelta(backendDir)
  const ffmpegNorm = normalizeForDelta(path.join(buildRoot, 'ffmpeg'))
  log(
    `normalised for differential updates: removed ${backendNorm.removed + ffmpegNorm.removed} cache entries, ` +
      `pinned ${backendNorm.touched + ffmpegNorm.touched} timestamps`
  )
}

/**
 * Write the bytecode we used to throw away.
 *
 * `unchecked-hash` stores the source hash in the .pyc and tells Python not to
 * validate it, which makes the file deterministic (no mtime inside) and means a
 * read-only install never recompiles. Measured on the backend import:
 * 1.16 s without bytecode, 0.72 s with it.
 */
function precompile(exe, backendDir) {
  for (const target of ['app', 'core', 'uploaders', path.join('python', 'Lib', 'site-packages')]) {
    const dir = path.join(backendDir, target)
    if (!fs.existsSync(dir)) continue
    try {
      execFileSync(
        exe,
        ['-m', 'compileall', '-q', '-f', '--invalidation-mode', 'unchecked-hash', dir],
        { cwd: backendDir, stdio: 'pipe' }
      )
    } catch {
      // A package with a syntax error under a different Python version must not
      // fail the build — the rest of the tree is still compiled.
      log(`compileall reported problems under ${target} (continuing)`)
    }
  }
  log('bytecode precompiled (deterministic, unchecked-hash)')
}

let lastImportError = ''

function canImport(exe, cwd) {
  // Import the app itself rather than a hand-written list of packages: that
  // list went stale the moment `sqlalchemy` was dropped (the database is
  // standard-library sqlite3) and aborted the whole build. The application is
  // the only honest answer to "can this runtime start?".
  //
  // The backend directory has to be named explicitly: in an embeddable runtime
  // the `.` entry of `python311._pth` resolves to the folder holding
  // python.exe, *not* to the process's working directory, so `import app.main`
  // alone cannot find the app.
  const probe = `import sys; sys.path.insert(0, r"${cwd}"); import app.main`
  try {
    execFileSync(exe, ['-c', probe], { cwd, stdio: 'pipe' })
    lastImportError = ''
    return true
  } catch (e) {
    lastImportError = String(e.stderr || e.stdout || e.message)
      .trim()
      .split('\n')
      .slice(-8)
      .join('\n')
    return false
  }
}

function installRequirements(exe, backendDir, frontendDir) {
  const pythonDir = path.dirname(exe)
  // Bootstrap pip inside the embeddable distribution.
  const getPip = path.join(backendDir, 'get-pip.py')
  if (!fs.existsSync(path.join(pythonDir, 'Lib', 'site-packages', 'pip'))) {
    log('bootstrapping pip')
    execFileSync(
      'powershell',
      [
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        `Invoke-WebRequest -Uri '${GET_PIP_URL}' -OutFile '${getPip}'`,
      ],
      { stdio: 'inherit' }
    )
    execFileSync(exe, [getPip, '--no-warn-script-location'], { cwd: backendDir, stdio: 'inherit' })
    fs.rmSync(getPip, { force: true })
  }

  // Runtime dependencies only — test tooling is not shipped.
  const src = path.resolve(frontendDir, '..', 'backend', 'requirements.txt')
  const runtimeReq = path.join(backendDir, 'requirements-runtime.txt')
  const lines = fs
    .readFileSync(src, 'utf8')
    .split(/\r?\n/)
    .filter((l) => !/^\s*pytest/.test(l))
  fs.writeFileSync(runtimeReq, lines.join('\n'))

  log('pip install -r requirements.txt (runtime subset)')
  execFileSync(exe, ['-m', 'pip', 'install', '--no-warn-script-location', '--no-cache-dir', '-r', runtimeReq], {
    cwd: backendDir,
    stdio: 'inherit',
  })
  fs.rmSync(runtimeReq, { force: true })

  // pip itself is a build-time tool.
  const sp = path.join(pythonDir, 'Lib', 'site-packages')
  for (const entry of fs.existsSync(sp) ? fs.readdirSync(sp) : []) {
    if (entry === 'pip' || entry.startsWith('pip-')) {
      fs.rmSync(path.join(sp, entry), { recursive: true, force: true })
    }
  }
}

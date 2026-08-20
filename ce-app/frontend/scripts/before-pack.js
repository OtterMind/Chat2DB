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
const FFMPEG_ZIP_URL = 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip'

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
  const zipPath = path.join(ffmpegDir, 'ffmpeg-release.zip')
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
  if (!sitePackages) {
    log('no site-packages found in the CI virtualenv — skipping')
    return
  }

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

  // Move the dependencies installed by CI into the portable runtime.
  const target = path.join(work, 'Lib', 'site-packages')
  fs.mkdirSync(path.dirname(target), { recursive: true })
  log(`moving site-packages -> ${target}`)
  fs.renameSync(sitePackages, target)

  // Drop build-time only artefacts to keep the installer smaller.
  for (const entry of fs.readdirSync(target)) {
    if (entry === 'pip' || entry.startsWith('pip-')) {
      fs.rmSync(path.join(target, entry), { recursive: true, force: true })
    }
  }

  fs.rmSync(pythonDir, { recursive: true, force: true })
  fs.renameSync(work, pythonDir)

  // Sanity check: the portable interpreter must be able to import FastAPI.
  execFileSync(path.join(pythonDir, 'python.exe'), ['-c', 'import fastapi, uvicorn; print("portable backend OK")'], {
    cwd: backendDir,
    stdio: 'inherit',
  })
  log('portable backend runtime ready')
}

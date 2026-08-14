import { createHash } from 'node:crypto'
import { createWriteStream, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { pipeline } from 'node:stream/promises'
import { Readable } from 'node:stream'

const root = path.resolve('resources/binaries')
const partials = new Set()
mkdirSync(root, { recursive: true })

function cleanup() {
  for (const file of partials) rmSync(file, { force: true })
}
process.once('SIGINT', () => { cleanup(); process.exit(130) })
process.once('SIGTERM', () => { cleanup(); process.exit(143) })

async function download(url, file) {
  const partial = `${file}.partial`
  partials.add(partial)
  rmSync(partial, { force: true })
  const response = await fetch(url, { redirect: 'follow', headers: { 'User-Agent': 'youtube-auto-uploader-build/1.0' } })
  if (!response.ok || !response.body) throw new Error(`Download failed (${response.status}): ${url}`)
  await pipeline(Readable.fromWeb(response.body), createWriteStream(partial))
  renameSync(partial, file)
  partials.delete(partial)
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex').toLowerCase()
}

function verify(file, expected, label) {
  const actual = sha256(file)
  if (!/^[a-f0-9]{64}$/.test(expected) || actual !== expected.toLowerCase()) {
    rmSync(file, { force: true })
    throw new Error(`${label} SHA-256 verification failed. Expected ${expected}; received ${actual}`)
  }
}

async function fetchYtDlp() {
  const exe = path.join(root, 'yt-dlp.exe')
  if (existsSync(exe)) return
  const response = await fetch('https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest', {
    headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'youtube-auto-uploader-build/1.0' }
  })
  if (!response.ok) throw new Error(`Unable to resolve yt-dlp release (${response.status})`)
  const release = await response.json()
  const executable = release.assets.find((asset) => asset.name === 'yt-dlp.exe')
  const checksums = release.assets.find((asset) => asset.name === 'SHA2-256SUMS')
  if (!executable || !checksums) throw new Error('Official yt-dlp release assets are incomplete')
  const sumsResponse = await fetch(checksums.browser_download_url, { headers: { 'User-Agent': 'youtube-auto-uploader-build/1.0' } })
  if (!sumsResponse.ok) throw new Error('Unable to download yt-dlp checksums')
  const expected = (await sumsResponse.text()).split('\n').find((line) => /\syt-dlp\.exe\s*$/.test(line))?.trim().split(/\s+/)[0]
  if (!expected) throw new Error('yt-dlp.exe is absent from SHA2-256SUMS')
  await download(executable.browser_download_url, exe)
  verify(exe, expected, 'yt-dlp')
}

function extractZip(zip, destination) {
  rmSync(destination, { recursive: true, force: true })
  mkdirSync(destination, { recursive: true })
  if (process.platform === 'win32') {
    execFileSync('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command',
      'Expand-Archive -LiteralPath $args[0] -DestinationPath $args[1] -Force', zip, destination], { stdio: 'inherit' })
  } else {
    execFileSync('unzip', ['-q', zip, '-d', destination], { stdio: 'inherit' })
  }
}

async function fetchFfmpeg() {
  const ffmpeg = path.join(root, 'ffmpeg.exe')
  const ffprobe = path.join(root, 'ffprobe.exe')
  if (existsSync(ffmpeg) && existsSync(ffprobe)) return
  const base = 'https://www.gyan.dev/ffmpeg/builds'
  const zip = path.resolve('resources/ffmpeg-release-essentials.zip')
  const checksumResponse = await fetch(`${base}/ffmpeg-release-essentials.zip.sha256`)
  if (!checksumResponse.ok) throw new Error('Unable to download FFmpeg checksum')
  const expected = (await checksumResponse.text()).trim().split(/\s+/)[0]
  await download(`${base}/ffmpeg-release-essentials.zip`, zip)
  verify(zip, expected, 'FFmpeg archive')
  const unpack = path.resolve('resources/.ffmpeg-unpack')
  extractZip(zip, unpack)
  const folder = readdirSync(unpack).map((name) => path.join(unpack, name)).find((name) => existsSync(path.join(name, 'bin', 'ffmpeg.exe')))
  if (!folder) throw new Error('FFmpeg archive layout is not recognized')
  for (const name of ['ffmpeg.exe', 'ffprobe.exe']) {
    rmSync(path.join(root, name), { force: true })
    renameSync(path.join(folder, 'bin', name), path.join(root, name))
  }
  rmSync(zip, { force: true })
  rmSync(unpack, { recursive: true, force: true })
}

function createBackgroundMusic() {
  const output = path.resolve('resources/music/background_lofi.mp3')
  if (existsSync(output)) return
  mkdirSync(path.dirname(output), { recursive: true })
  execFileSync(path.join(root, 'ffmpeg.exe'), ['-hide_banner','-loglevel','error','-f','lavfi','-i','sine=frequency=220:duration=30:sample_rate=44100','-f','lavfi','-i','sine=frequency=277.18:duration=30:sample_rate=44100','-filter_complex','[0:a]volume=0.12[a0];[1:a]volume=0.08[a1];[a0][a1]amix=inputs=2,lowpass=f=2200,afade=t=in:d=2,afade=t=out:st=28:d=2[a]','-map','[a]','-codec:a','libmp3lame','-b:a','128k','-y',output], { stdio: 'inherit' })
}

try {
  await Promise.all([fetchYtDlp(), fetchFfmpeg()])
  createBackgroundMusic()
  for (const name of ['yt-dlp.exe', 'ffmpeg.exe', 'ffprobe.exe']) {
    const file = path.join(root, name)
    console.log(`${name}: ${(statSync(file).size / 1_048_576).toFixed(1)} MB, SHA-256 ${sha256(file)}`)
  }
} catch (error) {
  cleanup()
  throw error
}

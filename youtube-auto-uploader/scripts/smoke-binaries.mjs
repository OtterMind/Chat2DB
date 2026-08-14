import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, rmSync } from 'node:fs'
import path from 'node:path'

const binaries = path.resolve('resources/binaries')
const ffmpeg = path.join(binaries, 'ffmpeg.exe')
const ffprobe = path.join(binaries, 'ffprobe.exe')
const ytDlp = path.join(binaries, 'yt-dlp.exe')
for (const file of [ffmpeg, ffprobe, ytDlp]) {
  if (!existsSync(file)) throw new Error(`Missing binary: ${file}`)
}
const temporary = path.resolve('.binary-smoke')
const video = path.join(temporary, 'testsrc.mp4')
rmSync(temporary, { recursive: true, force: true })
mkdirSync(temporary, { recursive: true })
try {
  execFileSync(ffmpeg, ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=duration=1:size=320x180:rate=25', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-y', video], { stdio: 'inherit' })
  const duration = Number(execFileSync(ffprobe, ['-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', video], { encoding: 'utf8' }).trim())
  if (duration < 0.9 || duration > 1.2) throw new Error(`Unexpected synthetic video duration: ${duration}`)
  execFileSync(ytDlp, ['--version'], { stdio: 'inherit' })
  console.log(`Binary smoke passed; duration=${duration.toFixed(3)}s`)
} finally {
  rmSync(temporary, { recursive: true, force: true })
}

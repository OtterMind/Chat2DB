#!/usr/bin/env node
/**
 * Headless playback test for the program monitor.
 *
 * These are the three faults the user reported on 0.3.3, all of which compiled
 * perfectly and all of which are invisible to a type checker:
 *   1. the red playhead never moved while the preview played
 *   2. the diamond between two clips did not open the transition chooser
 *   3. playback stopped at the end of the first clip instead of rolling on
 *
 * The test drives a real Chromium against the dev server, puts two real media
 * files on the timeline, presses play and watches the clock.
 *
 * Usage:
 *   node scripts/playback-test.mjs --url http://127.0.0.1:5173 --a /abs/a.webm --b /abs/b.webm
 */
import { existsSync } from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const args = Object.fromEntries(
  process.argv.slice(2).map((a, i, all) => (a.startsWith('--') ? [a.slice(2), all[i + 1] ?? true] : []))
)

const BASE = args.url ?? process.env.CE_UI_URL ?? 'http://127.0.0.1:5173'
const A = args.a ?? process.env.CE_TEST_A
const B = args.b ?? process.env.CE_TEST_B
if (!A || !B) {
  console.error('two media files are required:  --a /abs/one.webm --b /abs/two.webm')
  process.exit(2)
}

function findChrome() {
  if (process.env.CHROME_PATH && existsSync(process.env.CHROME_PATH)) return process.env.CHROME_PATH
  return ['/tmp/chromium', '/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome'].find((p) =>
    existsSync(p)
  )
}

const puppeteer = require('puppeteer-core')
const executablePath = findChrome()
if (!executablePath) {
  console.error('No Chromium found. Set CHROME_PATH.')
  process.exit(2)
}

const failures = []
const ok = (label) => console.log(`  ok   ${label}`)
const bad = (label, detail) => {
  failures.push(`${label}${detail ? ` — ${detail}` : ''}`)
  console.log(`  FAIL ${label}${detail ? ` — ${detail}` : ''}`)
}

const browser = await puppeteer.launch({
  executablePath,
  headless: 'shell',
  args: [
    '--no-sandbox',
    '--disable-setuid-sandbox',
    '--disable-dev-shm-usage',
    '--autoplay-policy=no-user-gesture-required',
    '--mute-audio',
  ],
})

const page = await browser.newPage()
await page.setViewport({ width: 1440, height: 900 })
const errors = []
page.on('pageerror', (e) => errors.push(String(e)))
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))

await page.goto(`${BASE}/#/studio`, { waitUntil: 'networkidle2' })
await page.waitForFunction('Boolean(window.__ceEditor)', { timeout: 15000 })

// Two clips back to back on the video lane, exactly like an import would make.
await page.evaluate(
  (a, b) => {
    const store = window.__ceEditor.getState()
    store.clearTimeline()
    const add = (src, start, label, colour) =>
      window.__ceEditor.getState().addClip({
        trackId: 'v1',
        start,
        duration: 3,
        offset: 0,
        sourceDuration: 3,
        src,
        label,
        color: colour,
      })
    add(a, 0, 'first', '#6366F1')
    add(b, 3, 'second', '#6366F1')
    window.__ceEditor.getState().setPlayhead(0)
  },
  A,
  B
)
await new Promise((r) => setTimeout(r, 1200))

/* 1 — the playhead moves ---------------------------------------------------- */
await page.evaluate(() => window.__ceEditor.getState().togglePlay(true))
await new Promise((r) => setTimeout(r, 1500))
const afterStart = await page.evaluate(() => ({
  playhead: window.__ceEditor.getState().playhead,
  marker: document.querySelector('.tl__playhead')?.style.left ?? '',
  time: document.querySelector('video')?.currentTime ?? -1,
}))
if (afterStart.playhead > 0.6) ok(`playhead advances (${afterStart.playhead.toFixed(2)}s)`)
else bad('playhead does not advance during playback', `playhead=${afterStart.playhead}`)
if (afterStart.marker && afterStart.marker !== '0px') ok(`red marker moved (left: ${afterStart.marker})`)
else bad('the red marker did not move', `left=${afterStart.marker}`)
if (afterStart.time > 0.4) ok(`video element is playing (${afterStart.time.toFixed(2)}s)`)
else bad('the video element is not playing', `currentTime=${afterStart.time}`)

/* 2 — playback rolls into the next clip ------------------------------------- */
await new Promise((r) => setTimeout(r, 3000))
const crossed = await page.evaluate(() => {
  const s = window.__ceEditor.getState()
  const active = s.clips.find((c) => s.playhead >= c.start && s.playhead < c.start + c.duration)
  return { playhead: s.playhead, playing: s.playing, label: active?.label ?? null }
})
if (crossed.playhead > 3.2 && crossed.label === 'second') ok(`rolled into the second clip (${crossed.playhead.toFixed(2)}s)`)
else bad('playback did not continue into the next clip', JSON.stringify(crossed))
if (crossed.playing) ok('still playing after the cut')
else bad('playback stopped at the cut')

/* 3 — it stops at the end of the timeline ----------------------------------- */
await new Promise((r) => setTimeout(r, 3500))
const atEnd = await page.evaluate(() => {
  const s = window.__ceEditor.getState()
  return { playhead: s.playhead, playing: s.playing }
})
if (!atEnd.playing && atEnd.playhead >= 5.5) ok(`stopped at the end (${atEnd.playhead.toFixed(2)}s)`)
else bad('did not stop cleanly at the end of the timeline', JSON.stringify(atEnd))

/* 4 — the junction diamond opens the transition chooser --------------------- */
await page.evaluate(() => {
  const s = window.__ceEditor.getState()
  s.togglePlay(false)
  s.setPanel(null)
  s.select(null)
})
await new Promise((r) => setTimeout(r, 300))
const junction = await page.$('.tl__junction')
if (!junction) bad('no junction diamond between the two clips')
else {
  await junction.click()
  await new Promise((r) => setTimeout(r, 500))
  const panel = await page.evaluate(() => ({
    panel: window.__ceEditor.getState().panel,
    selected: Boolean(window.__ceEditor.getState().selectedId),
    choices: document.querySelectorAll('.tb__transition').length,
  }))
  if (panel.panel === 'transition' && panel.selected) ok('the diamond opens the transition panel')
  else bad('the diamond did not open the transition panel', JSON.stringify(panel))
  if (panel.choices >= 20) ok(`${panel.choices} transitions offered`)
  else bad('the transition chooser is empty', `${panel.choices} options`)

  // Picking one must create a transition the render engine understands.
  const first = await page.$('.tb__transition')
  await first?.click()
  await new Promise((r) => setTimeout(r, 400))
  const created = await page.evaluate(() => window.__ceEditor.getState().transitions)
  if (created.length === 1) ok(`transition created (${created[0].type}, ${created[0].duration}s)`)
  else bad('picking a transition did not create one', JSON.stringify(created))
}

/* 5 — pause really pauses --------------------------------------------------- */
await page.evaluate(() => {
  window.__ceEditor.getState().setPlayhead(1)
  window.__ceEditor.getState().togglePlay(true)
})
await new Promise((r) => setTimeout(r, 900))
await page.evaluate(() => window.__ceEditor.getState().togglePlay(false))
const paused = await page.evaluate(() => ({
  head: window.__ceEditor.getState().playhead,
  paused: document.querySelector('video')?.paused,
}))
await new Promise((r) => setTimeout(r, 800))
const afterPause = await page.evaluate(() => ({
  head: window.__ceEditor.getState().playhead,
  time: document.querySelector('video')?.currentTime ?? -1,
  paused: document.querySelector('video')?.paused,
}))
if (paused.paused && afterPause.paused && Math.abs(afterPause.head - paused.head) < 0.05)
  ok('pause stops both the clock and the media')
else bad('pause did not stop playback', JSON.stringify({ paused, afterPause }))

/* 6 — scrubbing still works ------------------------------------------------- */
// The expected source time is derived from the clip itself: adding a transition
// ripples the second clip earlier, so a hard-coded number would lie.
await page.evaluate(() => window.__ceEditor.getState().setPlayhead(4.2))
await new Promise((r) => setTimeout(r, 700))
const scrub = await page.evaluate(() => {
  const s = window.__ceEditor.getState()
  const clip = s.clips.find((c) => s.playhead >= c.start && s.playhead < c.start + c.duration)
  return {
    head: s.playhead,
    expected: clip ? s.playhead - clip.start + clip.offset : -1,
    time: document.querySelector('video')?.currentTime ?? -1,
    paused: document.querySelector('video')?.paused,
  }
})
if (Math.abs(scrub.time - scrub.expected) < 0.35)
  ok(`seek follows the playhead (source ${scrub.time.toFixed(2)}s ≈ ${scrub.expected.toFixed(2)}s)`)
else bad('the preview did not follow a manual seek', JSON.stringify(scrub))

/* 7 — the effects actually reach the picture -------------------------------- */
const styleOf = () =>
  page.evaluate(() => {
    const layer = document.querySelector('.ed__layer')
    if (!layer) return null
    const cs = getComputedStyle(layer)
    return {
      opacity: Number(cs.opacity),
      transform: cs.transform,
      filter: cs.filter,
      clipPath: cs.clipPath,
      washes: document.querySelectorAll('.ed__wash').length,
    }
  })

await page.evaluate(() => {
  const s = window.__ceEditor.getState()
  s.setPlayhead(1)
  s.select(s.clips[0].id)
  s.resetProps(s.clips[0].id)
})
await new Promise((r) => setTimeout(r, 300))
const before = await styleOf()
if (before) ok('the preview renders a clip layer')
else bad('no clip layer in the preview')

const setProps = async (patch) => {
  await page.evaluate((p) => {
    const s = window.__ceEditor.getState()
    s.setProps(s.clips[0].id, p)
  }, patch)
  await new Promise((r) => setTimeout(r, 250))
  return styleOf()
}

const opacity = await setProps({ opacity: 0.4 })
if (opacity && Math.abs(opacity.opacity - 0.4) < 0.05) ok(`opacity applied (${opacity.opacity})`)
else bad('opacity is not applied in the preview', JSON.stringify(opacity))

const moved = await setProps({ opacity: 1, transform: { x: 0.2, y: -0.1, scale: 1.4, rotate: 30 } })
if (moved && moved.transform !== 'none' && moved.transform !== before?.transform)
  ok(`transform and rotation applied (${moved.transform})`)
else bad('transform/rotate is not applied in the preview', JSON.stringify(moved))

const graded = await setProps({
  transform: { x: 0, y: 0, scale: 1, rotate: 0 },
  filter: 'bw',
  adjust: { brightness: 0.2, contrast: 1.3, saturation: 0.5, temperature: 0.4, sharpen: 0, vignette: 0.5 },
})
if (graded && /grayscale/.test(graded.filter) && /brightness|contrast|saturate/.test(graded.filter))
  ok(`look and grade applied (${graded.filter})`)
else bad('filters/adjust are not applied in the preview', JSON.stringify(graded))
if (graded && graded.washes >= 2) ok(`tint and vignette painted (${graded.washes} washes)`)
else bad('tint/vignette missing', JSON.stringify(graded))

const cropped = await setProps({
  filter: 'none',
  adjust: { brightness: 0, contrast: 1, saturation: 1, temperature: 0, sharpen: 0, vignette: 0 },
  crop: { left: 0.2, top: 0.1, right: 0.1, bottom: 0 },
})
if (cropped && cropped.clipPath && cropped.clipPath !== 'none') ok(`crop applied (${cropped.clipPath})`)
else bad('crop is not applied in the preview', JSON.stringify(cropped))

// Animations are time based: the first frames of a fade-in must be transparent.
await setProps({ crop: { left: 0, top: 0, right: 0, bottom: 0 }, animIn: 'fade', animDuration: 1 })
await page.evaluate(() => window.__ceEditor.getState().setPlayhead(0.05))
await new Promise((r) => setTimeout(r, 250))
const animStart = await styleOf()
await page.evaluate(() => window.__ceEditor.getState().setPlayhead(1.5))
await new Promise((r) => setTimeout(r, 250))
const animLater = await styleOf()
if (animStart && animLater && animStart.opacity < 0.3 && animLater.opacity > 0.9)
  ok(`animation applied (${animStart.opacity.toFixed(2)} → ${animLater.opacity.toFixed(2)})`)
else bad('in/out animation is not applied in the preview', JSON.stringify({ animStart, animLater }))

/* 8 — a transition is really cross-faded ------------------------------------ */
const blend = await page.evaluate(async () => {
  const store = window.__ceEditor.getState()
  store.setProps(store.clips[0].id, { animIn: 'none' })
  const t = window.__ceEditor.getState().transitions[0]
  const from = window.__ceEditor.getState().clips.find((c) => c.id === t.fromClipId)
  const to = window.__ceEditor.getState().clips.find((c) => c.id === t.toClipId)
  const overlapStart = Math.max(from.start, to.start)
  const overlapEnd = Math.min(from.start + from.duration, to.start + to.duration)
  window.__ceEditor.getState().setPlayhead((overlapStart + overlapEnd) / 2)
  await new Promise((r) => setTimeout(r, 400))
  const layers = [...document.querySelectorAll('.ed__layer')]
  return { layers: layers.length, opacities: layers.map((l) => Number(getComputedStyle(l).opacity)) }
})
if (blend.layers === 2) ok('both clips are on screen during a transition')
else bad('the transition does not stack two clips', JSON.stringify(blend))
if (blend.opacities.some((o) => o > 0.2 && o < 0.9)) ok(`cross-fade in progress (${blend.opacities.join(', ')})`)
else bad('the transition is not cross-faded in the preview', JSON.stringify(blend))

/* 9 — the Delete key removes the selected clip ------------------------------ */
const deleted = await page.evaluate(async () => {
  const before = window.__ceEditor.getState().clips.length
  window.__ceEditor.getState().select(window.__ceEditor.getState().clips[0].id)
  return before
})
await page.keyboard.press('Delete')
await new Promise((r) => setTimeout(r, 300))
const afterDelete = await page.evaluate(() => window.__ceEditor.getState().clips.length)
if (afterDelete === deleted - 1) ok('the Delete key removes the selected clip')
else bad('the Delete key does nothing', `${deleted} → ${afterDelete}`)

// …and Ctrl+Z brings it back.
await page.keyboard.down('Control')
await page.keyboard.press('KeyZ')
await page.keyboard.up('Control')
await new Promise((r) => setTimeout(r, 300))
const restored = await page.evaluate(() => window.__ceEditor.getState().clips.length)
if (restored === deleted) ok('Ctrl+Z undoes it')
else bad('Ctrl+Z does not undo', `${afterDelete} → ${restored}`)

const hard = errors.filter((e) => !/favicon|ResizeObserver|DevTools/i.test(e))
if (hard.length) bad('console errors', hard.slice(0, 3).join(' | '))

await browser.close()

console.log('')
if (failures.length) {
  console.error(`playback test: ${failures.length} failure(s)`)
  process.exit(1)
}
console.log('playback test: all checks passed')

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
// Diagnostics: a reload mid-run kills the execution context and every later
// evaluate fails with "Promise was collected" — log navigations to see why.
page.on('framenavigated', (frame) => {
  if (process.env.CE_TEST_TRACE) console.log(`  ..   navigated → ${frame.url()}`)
})
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))

await page.goto(`${BASE}/#/studio`, { waitUntil: 'networkidle2' })
await page.waitForFunction('Boolean(window.__ceEditor)', { timeout: 15000 })

// An autosave left by an earlier run opens a modal that swallows every click.
await new Promise((r) => setTimeout(r, 800))
const dismissed = await page.evaluate(() => {
  const buttons = [...document.querySelectorAll('.ant-modal-wrap button')]
  const discard = buttons.find((b) => /Discard|دور/i.test(b.textContent ?? ''))
  if (discard) {
    discard.click()
    return true
  }
  return false
})
if (dismissed) await new Promise((r) => setTimeout(r, 500))

// a vertical file for the canvas-shape check, when one was provided
await page.evaluate((v) => {
  window.__ceTestVertical = v
}, args.vertical ?? process.env.CE_TEST_VERTICAL ?? A)

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
const afterStart = await page.evaluate(() => {
  const marker = document.querySelector('.tl__playhead')
  const view = document.querySelector('.tl__scroll')
  return {
    playhead: window.__ceEditor.getState().playhead,
    // In centred mode the marker stands still and the timeline scrolls under it,
    // so "did the picture move" is the scroll position, not the marker's left.
    centred: marker?.classList.contains('is-centred') ?? false,
    marker: marker?.style.left ?? '',
    scrollLeft: view?.scrollLeft ?? 0,
    time: document.querySelector('video')?.currentTime ?? -1,
  }
})
if (afterStart.playhead > 0.6) ok(`playhead advances (${afterStart.playhead.toFixed(2)}s)`)
else bad('playhead does not advance during playback', `playhead=${afterStart.playhead}`)
const movedOnScreen = afterStart.centred ? afterStart.scrollLeft > 20 : afterStart.marker !== '0px'
if (movedOnScreen)
  ok(
    afterStart.centred
      ? `timeline scrolls under the pinned playhead (${Math.round(afterStart.scrollLeft)}px)`
      : `red marker moved (left: ${afterStart.marker})`
  )
else bad('nothing moved while playing', JSON.stringify(afterStart))
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
const blend = await page.evaluate(() => (window.__pending = (async () => {
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
})()))
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

/* 10 — the layout the user asked for ---------------------------------------- */
const layout = await page.evaluate(() => ({
  zoomBarAboveTimeline: Boolean(document.querySelector('.tl__zoombar')),
  toolbarZoomSlider: Boolean(document.querySelector('.ed__zoom')),
  scaleInsideTimeline: Boolean(document.querySelector('.tl__corner .tl__cornerslider')),
  pageHeading: Boolean(document.querySelector('.ce-page__head')),
}))
if (!layout.zoomBarAboveTimeline && !layout.toolbarZoomSlider) ok('the separate scale bar and the magnifiers are gone')
else bad('the old zoom controls are still there', JSON.stringify(layout))
if (layout.scaleInsideTimeline) ok('the scale control lives inside the timeline')
else bad('the timeline has no scale control', JSON.stringify(layout))
if (!layout.pageHeading) ok('the editor has no heading strip above it')
else bad('the editor still has a page heading', JSON.stringify(layout))

// Ctrl + wheel zooms the timeline.
const zoomBefore = await page.evaluate(() => window.__ceEditor.getState().pxPerSecond)
const lane = await page.$('.tl__scroll')
await lane.scrollIntoView()
await new Promise((r) => setTimeout(r, 300))
const laneBox = await lane.boundingBox()
// aim inside the visible part of the lane: its centre can sit below the fold
const aimY = Math.min(laneBox.y + 30, page.viewport().height - 20)
await page.mouse.move(laneBox.x + laneBox.width / 2, aimY)
await page.keyboard.down('Control')
await page.mouse.wheel({ deltaY: -240 })
await page.keyboard.up('Control')
await new Promise((r) => setTimeout(r, 300))
const zoomAfter = await page.evaluate(() => window.__ceEditor.getState().pxPerSecond)
if (zoomAfter > zoomBefore) ok(`Ctrl + wheel zooms the timeline (${zoomBefore.toFixed(0)} → ${zoomAfter.toFixed(0)} px/s)`)
else bad('Ctrl + wheel does not zoom', `${zoomBefore} → ${zoomAfter} at ${JSON.stringify(laneBox)}`)

/* 11 — the monitor takes the shape of the footage --------------------------- */
const shapes = await page.evaluate(() => (window.__pending = (async () => {
  const store = window.__ceEditor.getState()
  store.clearTimeline()
  const measure = async () => {
    await new Promise((r) => setTimeout(r, 500))
    const box = document.querySelector('.ed__stagewrap')?.getBoundingClientRect()
    return box ? Number((box.width / box.height).toFixed(3)) : null
  }
  const state = () => window.__ceEditor.getState()
  state().addClip({
    trackId: 'v1', start: 0, duration: 4, offset: 0, sourceDuration: 4,
    src: window.__ceTestVertical, label: 'vertical', color: '#6366F1', width: 360, height: 640,
  })
  state().setPlayhead(1)
  const auto = await measure()
  state().setAspect('16:9')
  const wide = await measure()
  state().setAspect('1:1')
  const square = await measure()
  state().setAspect('auto')
  return { auto, wide, square }
})()))
if (shapes.auto !== null && Math.abs(shapes.auto - 0.5625) < 0.02)
  ok(`the monitor follows a vertical clip (ratio ${shapes.auto})`)
else bad('a vertical video is not shown in its own shape', JSON.stringify(shapes))
if (Math.abs(shapes.wide - 1.7778) < 0.05 && Math.abs(shapes.square - 1) < 0.05)
  ok('the ratio panel reshapes the monitor')
else bad('the chosen ratio does not reshape the monitor', JSON.stringify(shapes))

/* 11b — clips show real frames, not coloured blocks -------------------------- */
const strip = await page.evaluate(async () => {
  const store = window.__ceEditor
  store.getState().setAspect('auto')
  await new Promise((r) => setTimeout(r, 1500))
  const images = [...document.querySelectorAll('.tl__clip .tl__strip img')]
  return {
    count: images.length,
    loaded: images.filter((img) => img.naturalWidth > 0).length,
    src: images[0]?.getAttribute('src') ?? null,
  }
})
if (strip.count > 0) ok(`the clip shows a film strip (${strip.count} frames)`)
else bad('timeline clips have no thumbnails')
if (strip.loaded > 0) ok(`${strip.loaded} frames decoded from the backend`)
else bad('the thumbnails never loaded', JSON.stringify(strip))

/* 11c — the tools taken off the home screen are in the rail ------------------ */
const rail = await page.evaluate(() => {
  const labels = [...document.querySelectorAll('.tb__tool .tb__label')].map((n) => n.textContent?.trim())
  return { labels, count: labels.length }
})
const movedTools = ['Smart Captions', 'Silence Removal', 'Voice Over', 'Auto B-Roll', 'Translate & Dub']
const missing = movedTools.filter((label) => !rail.labels.includes(label))
if (missing.length === 0) ok(`the moved tools are in the edit rail (${rail.count} tools)`)
else bad('tools removed from home are missing in the rail', missing.join(', '))

/* 11d — centred mode: pinned playhead, timeline scrolls -------------------- */
const centred = await page.evaluate(() => (window.__pending = (async () => {
  const store = window.__ceEditor
  store.getState().setPlayhead(2)
  await new Promise((r) => setTimeout(r, 400))
  const view = document.querySelector('.tl__scroll')
  const marker = document.querySelector('.tl__playhead')
  const rect = marker.getBoundingClientRect()
  const viewRect = view.getBoundingClientRect()
  const centreOffset = Math.abs(rect.left + rect.width / 2 - (viewRect.left + viewRect.width / 2))
  const scrollAt2 = view.scrollLeft

  // scrolling by hand must move the playhead, not just the picture
  view.scrollLeft = scrollAt2 + 200
  await new Promise((r) => setTimeout(r, 300))
  const afterScroll = store.getState().playhead

  return {
    pinnedToCentre: centreOffset < 6,
    scrollFollowsPlayhead: Math.abs(scrollAt2 - 2 * store.getState().pxPerSecond) < 3,
    playheadFollowsScroll: afterScroll > 2.05,
  }
})()))
if (centred.pinnedToCentre) ok('the playhead is pinned to the middle of the timeline')
else bad('the playhead is not centred', JSON.stringify(centred))
if (centred.scrollFollowsPlayhead) ok('the timeline scrolls to the playhead')
else bad('the timeline does not follow the playhead', JSON.stringify(centred))
if (centred.playheadFollowsScroll) ok('scrolling the timeline scrubs')
else bad('scrolling the timeline does not scrub', JSON.stringify(centred))

/* 11e — editing proxies ----------------------------------------------------- */
if (args.big ?? process.env.CE_TEST_BIG) {
  const big = args.big ?? process.env.CE_TEST_BIG
  const proxyResult = await page.evaluate((path) => (window.__pending = (async () => {
    const started = await fetch('http://127.0.0.1:8742/api/media/proxy', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ path }),
    }).then((r) => r.json())
    let state = started
    for (let i = 0; i < 90 && state.status === 'building'; i++) {
      await new Promise((r) => setTimeout(r, 1000))
      state = await fetch(
        `http://127.0.0.1:8742/api/media/proxy?path=${encodeURIComponent(path)}`
      ).then((r) => r.json())
    }
    return state
  })()), big)
  if (proxyResult.status === 'ready' && proxyResult.proxy) ok('a 720p editing proxy was built for big footage')
  else bad('the proxy was not built', JSON.stringify(proxyResult))

  // …and the preview plays the proxy while the model still points at the source
    const usesProxy = await page.evaluate((path, proxyPath) => (window.__pending = (() => {
    const store = window.__ceEditor
    store.getState().clearTimeline()
    store.getState().addClip({
      trackId: 'v1', start: 0, duration: 3, offset: 0, sourceDuration: 3,
      src: path, label: 'big', color: '#6366F1', width: 2560, height: 1440,
    })
    store.getState().setProxy(path, proxyPath)
    store.getState().setPlayhead(1)
    return new Promise((resolve) =>
      setTimeout(() => {
        const video = document.querySelector('video')
        resolve({
          videoSrc: decodeURIComponent(video?.getAttribute('src') ?? ''),
          clipSrc: store.getState().clips[0].src,
        })
      }, 600)
    )
  })()), big, proxyResult.proxy)
  if (usesProxy.videoSrc.includes(proxyResult.proxy) && usesProxy.clipSrc === big)
    ok('the preview plays the proxy while the project keeps the original')
  else bad('the preview did not switch to the proxy', JSON.stringify(usesProxy))
}

/* 11f — ripple, roll and slip ----------------------------------------------- */
const trims = await page.evaluate(() => {
  const store = window.__ceEditor
  const fresh = () => {
    store.getState().clearTimeline()
    // real paths, so the film strip does not spam 404s during the test
    const src = window.__ceTestVertical
    const a = store.getState().addClip({
      trackId: 'v1', start: 0, duration: 4, offset: 2, sourceDuration: 12, src, label: 'A', color: '#111',
    })
    const b = store.getState().addClip({
      trackId: 'v1', start: 4, duration: 4, offset: 0, sourceDuration: 12, src, label: 'B', color: '#222',
    })
    return [a, b]
  }
  const clip = (id) => store.getState().clips.find((c) => c.id === id)

  // ripple trim: shortening A must pull B back, leaving no gap
  let [a, b] = fresh()
  store.getState().rippleTrim(a, 'end', 3)
  const ripple = { a: clip(a).duration, bStart: clip(b).start }

  // roll: the cut moves, the pair keeps its total length
  ;[a, b] = fresh()
  const totalBefore = clip(a).duration + clip(b).duration
  store.getState().rollEdit(a, 5)
  const roll = {
    aDuration: clip(a).duration,
    bStart: clip(b).start,
    bOffset: clip(b).offset,
    total: clip(a).duration + clip(b).duration,
    totalBefore,
  }

  // slip: the window inside the clip moves, the clip does not
  ;[a] = fresh()
  const beforeSlip = { start: clip(a).start, duration: clip(a).duration, offset: clip(a).offset }
  store.getState().slipClip(a, 1.5)
  const slip = { ...beforeSlip, offset: clip(a).offset, start: clip(a).start, duration: clip(a).duration }

  // slip must stop at the end of the source
  store.getState().slipClip(a, 999)
  const clamped = clip(a).offset

  // ripple delete: the hole closes
  ;[a, b] = fresh()
  store.getState().rippleDelete(a)
  const deleted = { count: store.getState().clips.length, bStart: clip(b).start }

  return { ripple, roll, slip, clamped, deleted, sourceDuration: 12 }
})

if (Math.abs(trims.ripple.a - 3) < 0.01 && Math.abs(trims.ripple.bStart - 3) < 0.01)
  ok('ripple trim closes the gap behind it')
else bad('ripple trim left a gap', JSON.stringify(trims.ripple))

if (
  Math.abs(trims.roll.aDuration - 5) < 0.01 &&
  Math.abs(trims.roll.bStart - 5) < 0.01 &&
  Math.abs(trims.roll.bOffset - 1) < 0.01 &&
  Math.abs(trims.roll.total - trims.roll.totalBefore) < 0.01
)
  ok('roll moves the cut and keeps the total length')
else bad('roll edit is wrong', JSON.stringify(trims.roll))

if (
  Math.abs(trims.slip.offset - 3.5) < 0.01 &&
  trims.slip.start === 0 &&
  Math.abs(trims.slip.duration - 4) < 0.01
)
  ok('slip changes the content, not the position')
else bad('slip moved the clip', JSON.stringify(trims.slip))

if (Math.abs(trims.clamped - (trims.sourceDuration - 4)) < 0.01) ok('slip stops at the end of the source')
else bad('slip ran past the end of the source', String(trims.clamped))

if (trims.deleted.count === 1 && Math.abs(trims.deleted.bStart) < 0.01) ok('ripple delete closes the hole')
else bad('ripple delete left a hole', JSON.stringify(trims.deleted))

/* 11f2 — keyframes animate the preview -------------------------------------- */
// Driven from Node in short steps: a long-running page promise gets collected
// by the browser and the whole run dies with "Promise was collected".
await page.evaluate(() => {
  const store = window.__ceEditor
  store.getState().clearTimeline()
  const id = store.getState().addClip({
    trackId: 'v1', start: 0, duration: 4, offset: 0, sourceDuration: 4,
    src: window.__ceTestVertical, label: 'K', color: '#6366F1', width: 360, height: 640,
  })
  store.getState().select(id)
  store.getState().setKeyframe(id, 0, { scale: 0.4, x: -0.25, rotate: 0, volume: 0.1 })
  store.getState().setKeyframe(id, 4, { scale: 1.2, x: 0.25, rotate: 60, volume: 1 })
})
await new Promise((r) => setTimeout(r, 700))

const readKeyframed = async (at) => {
  await page.evaluate((playhead) => window.__ceEditor.getState().setPlayhead(playhead), at)
  await new Promise((r) => setTimeout(r, 450))
  return page.evaluate(() => {
    const layer = document.querySelector('.ed__layer')
    const video = document.querySelector('video')
    const matrix = new DOMMatrixReadOnly(getComputedStyle(layer).transform)
    return {
      scale: Math.hypot(matrix.a, matrix.b),
      angle: Math.round((Math.atan2(matrix.b, matrix.a) * 180) / Math.PI),
      x: matrix.e,
      volume: video?.volume ?? -1,
    }
  })
}
const kfStart = await readKeyframed(0.05)
const kfMiddle = await readKeyframed(2)
const kfEnd = await readKeyframed(3.95)
const kfMarkers = await page.evaluate(() => document.querySelectorAll('.tl__key').length)

if (kfStart.scale < kfMiddle.scale && kfMiddle.scale < kfEnd.scale)
  ok(`scale keyframes interpolate (${kfStart.scale.toFixed(2)} → ${kfMiddle.scale.toFixed(2)} → ${kfEnd.scale.toFixed(2)})`)
else bad('scale keyframes are not interpolated', JSON.stringify({ kfStart, kfMiddle, kfEnd }))
if (kfStart.x < kfMiddle.x && kfMiddle.x < kfEnd.x) ok('position keyframes interpolate')
else bad('position keyframes are not interpolated', JSON.stringify({ kfStart, kfMiddle, kfEnd }))
if (kfEnd.angle > kfMiddle.angle && kfMiddle.angle > kfStart.angle)
  ok(`rotation keyframes interpolate (${kfStart.angle}° → ${kfEnd.angle}°)`)
else bad('rotation keyframes are not interpolated', JSON.stringify({ kfStart, kfMiddle, kfEnd }))
if (kfMiddle.volume > kfStart.volume && kfEnd.volume > kfMiddle.volume)
  ok(`volume keyframes ramp the preview (${kfStart.volume.toFixed(2)} → ${kfEnd.volume.toFixed(2)})`)
else bad('volume keyframes do not affect the preview', JSON.stringify({ kfStart, kfMiddle, kfEnd }))
if (kfMarkers === 2) ok('the timeline shows a marker per keyframe')
else bad('keyframe markers missing on the clip', `${kfMarkers} markers`)

// Halfway between two keys the value must be the average — the same linear rule
// the compositor's expression uses, so preview and export cannot drift apart.
const midpoint = await page.evaluate(() => {
  const clip = window.__ceEditor.getState().clips[0]
  const { sampleChannel } = window.__ceSampler ?? {}
  const scaleAt2 = clip.keyframes && clip.keyframes.length === 2
    ? clip.keyframes[0].scale + (clip.keyframes[1].scale - clip.keyframes[0].scale) * 0.5
    : null
  void sampleChannel
  return scaleAt2
})
if (midpoint !== null && Math.abs(kfMiddle.scale - midpoint) < 0.03)
  ok(`the midpoint is the average (${kfMiddle.scale.toFixed(3)} ≈ ${midpoint.toFixed(3)})`)
else bad('interpolation is not linear', JSON.stringify({ mid: kfMiddle.scale, expected: midpoint }))

/* 11g — mute is sound, hide is picture -------------------------------------- */
const mute = await page.evaluate(() => (window.__pending = (async () => {
  const store = window.__ceEditor
  store.getState().clearTimeline()
  const id = store.getState().addClip({
    trackId: 'v1', start: 0, duration: 4, offset: 0, sourceDuration: 4,
    src: window.__ceTestVertical, label: 'A', color: '#6366F1', width: 360, height: 640,
  })
  store.getState().select(id)
  store.getState().setPlayhead(1)
  const settle = () => new Promise((r) => setTimeout(r, 500))
  await settle()
  const read = () => {
    const video = document.querySelector('video')
    return { picture: Boolean(video), elMuted: video?.muted ?? null, volume: video?.volume ?? null }
  }
  const before = read()

  // the clip's own Mute tool
  store.getState().setProps(id, { muted: true })
  await settle()
  const clipMuted = read()
  store.getState().setProps(id, { muted: false })

  // the lane's Mute button next to the timeline
  store.getState().toggleMute('v1')
  await settle()
  const laneMuted = read()
  store.getState().toggleMute('v1')

  // …and the separate eye, which is the one that hides the picture
  store.getState().toggleHidden('v1')
  await settle()
  const laneHidden = read()
  store.getState().toggleHidden('v1')
  await settle()
  return { before, clipMuted, laneMuted, laneHidden }
})()))
if (mute.before.picture && !mute.before.elMuted) ok('the clip plays with sound to begin with')
else bad('the clip did not start unmuted', JSON.stringify(mute.before))
if (mute.clipMuted.elMuted && mute.clipMuted.picture) ok('the clip Mute tool silences the clip')
else bad('the clip Mute tool does nothing', JSON.stringify(mute.clipMuted))
if (mute.laneMuted.elMuted && mute.laneMuted.picture)
  ok('muting the lane silences it and keeps the picture')
else bad('muting the lane blanked the preview', JSON.stringify(mute.laneMuted))
if (!mute.laneHidden.picture) ok('the eye hides the picture (mute no longer does)')
else bad('hiding the lane did not remove the picture', JSON.stringify(mute.laneHidden))

/* 11h — chrome fades away inside a section ---------------------------------- */
const chrome = await page.evaluate(() => (window.__pending = (async () => {
  const wait = (ms) => new Promise((r) => setTimeout(r, ms))
  location.hash = '#/'
  await wait(700)
  const onLauncher = Boolean(document.querySelector('.ce-header'))
  location.hash = '#/studio'
  await wait(900)
  const inSection = Boolean(document.querySelector('.ce-header'))
  const revealPill = Boolean(document.querySelector('.ce-reveal'))
  document.querySelector('.ce-reveal')?.click()
  await wait(700)
  const afterReveal = Boolean(document.querySelector('.ce-header'))
  return { onLauncher, inSection, revealPill, afterReveal }
})()))
if (chrome.onLauncher) ok('the launcher keeps its header and tabs')
else bad('the header is missing on the home screen')
if (!chrome.inSection) ok('the header fades away inside a section (full screen)')
else bad('the header is still there inside a section', JSON.stringify(chrome))
if (chrome.revealPill && chrome.afterReveal) ok('the menu can be brought back')
else bad('there is no way back to the menu', JSON.stringify(chrome))

/* 12 — the home screen starts a video --------------------------------------- */
await page.goto(`${BASE}/#/`, { waitUntil: 'networkidle2' })
await new Promise((r) => setTimeout(r, 900))
const home = await page.evaluate(() => ({
  starters: document.querySelectorAll('.ce-start__main').length,
  recents: Boolean(document.querySelector('.ce-reel, .ce-empty')),
  editorTileSaysSoon: [...document.querySelectorAll('.ce-tile')].some(
    (tile) => /Editor/.test(tile.textContent ?? '') && /SOON/i.test(tile.textContent ?? '')
  ),
  clipTools: [...document.querySelectorAll('.ce-tile')]
    .map((tile) => tile.textContent ?? '')
    .filter((text) => /Voice Over|Auto B-Roll|Translate|Silence Removal|Smart Captions/.test(text)).length,
}))
if (home.starters === 2) ok('the home screen leads with New video / Open editor')
else bad('the home screen has no starting cards', JSON.stringify(home))
if (home.recents) ok('the recent projects strip is there')
else bad('no recent projects strip on the home screen')
if (!home.editorTileSaysSoon) ok('the editor tile no longer claims to be "soon"')
else bad('the editor tile still says "soon"')
if (home.clipTools === 0) ok('clip tools are no longer on the home screen')
else bad('clip tools are still on the home screen', `${home.clipTools} tiles`)

const hard = errors.filter(
  // antd's static-function advisory is a warning about theming, handled in CSS.
  (e) => !/favicon|ResizeObserver|DevTools|antd: Modal|Static function can not consume context/i.test(e)
)
if (hard.length) bad('console errors', hard.slice(0, 3).join(' | '))

await browser.close()

console.log('')
if (failures.length) {
  console.error(`playback test: ${failures.length} failure(s)`)
  process.exit(1)
}
console.log('playback test: all checks passed')

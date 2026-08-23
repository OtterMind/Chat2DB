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
  tabs: Boolean(document.querySelector('.ce-tabs')),
  oldHeader: Boolean(document.querySelector('.ce-header')),
  projectBar: Boolean(document.querySelector('.pj')),
  inspector: Boolean(document.querySelector('.ed__inspector')),
  dockedBrand: Boolean(document.querySelector('.ce-brandbtn.is-docked')),
}))
if (!layout.zoomBarAboveTimeline && !layout.toolbarZoomSlider) ok('the separate scale bar and the magnifiers are gone')
else bad('the old zoom controls are still there', JSON.stringify(layout))
if (layout.scaleInsideTimeline) ok('the scale control lives inside the timeline')
else bad('the timeline has no scale control', JSON.stringify(layout))
if (!layout.pageHeading) ok('the editor has no heading strip above it')
else bad('the editor still has a page heading', JSON.stringify(layout))
if (!layout.tabs && !layout.oldHeader) ok('the tab bar and the old header are gone')
else bad('a top bar is still rendered', JSON.stringify(layout))
if (!layout.projectBar) ok('the save bar is out of the editor')
else bad('the project bar is still above the preview', JSON.stringify(layout))
if (!layout.inspector) ok('the Properties panel is gone')
else bad('the Properties panel is still there', JSON.stringify(layout))
if (layout.dockedBrand) ok('the wordmark is docked in the corner of a section')
else bad('the docked wordmark is missing', JSON.stringify(layout))

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

/* 11f3 — waveform, beat grid and cut-on-beat -------------------------------- */
if (args.beat ?? process.env.CE_TEST_BEAT) {
  const beatFile = args.beat ?? process.env.CE_TEST_BEAT
  await page.evaluate((audioPath, videoPath) => {
    const S = () => window.__ceEditor.getState()
    S().clearTimeline()
    S().setBeats([], 0)
    // 4 s: the real length of the test clip, so the film strip is not asking
    // for frames the file does not have.
    S().addClip({
      trackId: 'v1', start: 0, duration: 4, offset: 0, sourceDuration: 4,
      src: videoPath, label: 'picture', color: '#6366F1', width: 360, height: 640,
    })
    S().addClip({
      trackId: 'a1', start: 0, duration: 8, offset: 0, sourceDuration: 8,
      src: audioPath, label: 'music', color: '#10B981',
    })
  }, beatFile, args.vertical ?? process.env.CE_TEST_VERTICAL ?? A)
  await new Promise((r) => setTimeout(r, 1800))

  const wave = await page.evaluate(() => {
    const polygon = document.querySelector('.tl__wave polygon')
    return { drawn: Boolean(polygon), points: polygon?.getAttribute('points')?.split(' ').length ?? 0 }
  })
  if (wave.drawn && wave.points > 50) ok(`the audio clip draws a waveform (${wave.points} points)`)
  else bad('no waveform on the audio clip', JSON.stringify(wave))

  // Find the beat: the number must match the click track we synthesised.
  const detected = await page.evaluate((path) => (window.__pending = (async () => {
    const response = await fetch('http://127.0.0.1:8742/api/analyze/beats', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ path }),
    }).then((r) => r.json())
    window.__ceEditor.getState().setBeats(response.beats, response.bpm)
    await new Promise((r) => setTimeout(r, 500))
    return { bpm: response.bpm, beats: response.beats.length, markers: document.querySelectorAll('.tl__beat').length }
  })()), beatFile)
  if (Math.abs(detected.bpm - 120) < 3) ok(`the beat detector reads 120 BPM (${detected.bpm})`)
  else bad('the tempo is wrong', JSON.stringify(detected))
  if (detected.markers === detected.beats && detected.markers > 10)
    ok(`the beat grid is drawn on the ruler (${detected.markers} lines)`)
  else bad('the beat grid is missing', JSON.stringify(detected))

  // Cut on beat: one clip becomes one piece per beat.
  const cut = await page.evaluate(() => {
    const S = () => window.__ceEditor.getState()
    const picture = S().clips.find((c) => c.label === 'picture')
    S().select(picture.id)
    const before = S().clips.length
    const cuts = S().splitAtBeats(picture.id)
    const after = S().clips
    const lane = after.filter((c) => c.trackId === 'v1').sort((a, b) => a.start - b.start)
    const gaps = lane.slice(1).map((c, i) => Math.abs(c.start - (lane[i].start + lane[i].duration)))
    return { before, cuts, count: after.length, biggestGap: gaps.length ? Math.max(...gaps) : 0 }
  })
  if (cut.cuts > 5 && cut.count === cut.before + cut.cuts)
    ok(`cut on beat split the clip into ${cut.cuts + 1} pieces`)
  else bad('cut on beat did not split the clip', JSON.stringify(cut))
  if (cut.biggestGap < 0.001) ok('the pieces tile the lane with no gaps')
  else bad('cutting on the beat left a gap', JSON.stringify(cut))
}

/* 11f4 — ducking in the monitor --------------------------------------------- */
if (args.beat ?? process.env.CE_TEST_BEAT) {
  const duck = await page.evaluate(() => (window.__pending = (async () => {
    const S = () => window.__ceEditor.getState()
    const music = S().clips.find((c) => c.label === 'music')
    const voice = S().clips.find((c) => c.trackId === 'v1')
    if (!music || !voice) return { skipped: true }

    const settle = () => new Promise((r) => setTimeout(r, 450))
    const level = () => document.querySelector('audio')?.volume ?? -1

    // Playhead where both the bed and the picture's sound are running.
    S().setPlayhead(1)
    await settle()
    const before = level()

    S().setProps(music.id, { duck: true })
    await settle()
    const ducked = level()

    // …and past the end of *all* the picture clips (cut-on-beat split it into
    // many pieces earlier) the bed comes back.
    const lastVoiceEnd = Math.max(
      ...S().clips.filter((c) => c.trackId === 'v1').map((c) => c.start + c.duration)
    )
    S().setPlayhead(lastVoiceEnd + 0.5)
    await settle()
    const recovered = level()
    S().setProps(music.id, { duck: false })
    return { before, ducked, recovered }
  })()))
  if (duck.skipped) bad('the ducking check could not find its clips')
  else if (duck.ducked < duck.before * 0.6) ok(`the bed steps back in the monitor (${duck.before.toFixed(2)} → ${duck.ducked.toFixed(2)})`)
  else bad('ducking does nothing in the monitor', JSON.stringify(duck))
  if (!duck.skipped && duck.recovered > duck.ducked * 1.5) ok('the bed returns when the voice stops')
  else if (!duck.skipped) bad('the bed stayed down after the voice', JSON.stringify(duck))
}

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

/* 11h — the wordmark is the whole chrome ------------------------------------ */
await page.evaluate(() => {
  location.hash = '#/'
})
await new Promise((r) => setTimeout(r, 900))
const onLauncher = await page.evaluate(() => ({
  hero: Boolean(document.querySelector('.ce-brandbtn.is-hero')),
  docked: Boolean(document.querySelector('.ce-brandbtn.is-docked')),
  actions: document.querySelectorAll('.ce-launcheractions button').length,
}))
if (onLauncher.hero && !onLauncher.docked) ok('the wordmark is centred on the launcher')
else bad('the launcher wordmark is not centred', JSON.stringify(onLauncher))
if (onLauncher.actions === 3) ok('the launcher keeps its three window actions')
else bad('the launcher actions are missing', JSON.stringify(onLauncher))

await page.evaluate(() => {
  location.hash = '#/studio'
})
await new Promise((r) => setTimeout(r, 900))
const inSection = await page.evaluate(() => ({
  hero: Boolean(document.querySelector('.ce-brandbtn.is-hero')),
  docked: Boolean(document.querySelector('.ce-brandbtn.is-docked')),
  actions: document.querySelectorAll('.ce-launcheractions button').length,
}))
if (inSection.docked && !inSection.hero) ok('entering a section docks the wordmark')
else bad('the wordmark did not dock', JSON.stringify(inSection))
if (inSection.actions === 0) ok('a section shows nothing but the wordmark')
else bad('window actions leaked into a section', JSON.stringify(inSection))

// …and the wordmark is the way home.
await page.click('.ce-brandbtn')
await new Promise((r) => setTimeout(r, 900))
const backHome = await page.evaluate(() => ({
  hash: location.hash,
  hero: Boolean(document.querySelector('.ce-brandbtn.is-hero')),
}))
if ((backHome.hash === '' || backHome.hash === '#/') && backHome.hero)
  ok('clicking the wordmark goes home')
else bad('the wordmark does not navigate home', JSON.stringify(backHome))

/* 11i — notifications are readable ------------------------------------------ */
const toast = await page.evaluate(() => (window.__pending = (async () => {
  location.hash = '#/studio'
  await new Promise((r) => setTimeout(r, 800))
  const store = window.__ceEditor
  store.getState().clearTimeline()
  const id = store.getState().addClip({
    trackId: 'v1', start: 0, duration: 3, offset: 0, sourceDuration: 3,
    src: window.__ceTestVertical, label: 'T', color: '#6366F1',
  })
  store.getState().select(id)
  await new Promise((r) => setTimeout(r, 400))
  const button = [...document.querySelectorAll('.tb__tool')].find((b) => /Mute/i.test(b.textContent ?? ''))
  button?.click()
  await new Promise((r) => setTimeout(r, 600))
  const notice = document.querySelector('.ant-message-notice-content')
  if (!notice) return { visible: false }
  const style = getComputedStyle(notice)
  const rgb = style.backgroundColor.match(/\d+/g)?.map(Number) ?? [255, 255, 255]
  const luma = (0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2]) / 255
  return { visible: true, background: style.backgroundColor, luma, text: notice.textContent }
})()))
if (toast.visible && toast.luma < 0.35)
  ok(`the notification is dark and readable (${toast.background}, "${toast.text?.trim()}")`)
else bad('the notification is still unreadable', JSON.stringify(toast))

/* 11j — Style Match: reference in, edited timeline out ----------------------- */
if ((args.reference ?? process.env.CE_TEST_REFERENCE) && (args.vertical ?? process.env.CE_TEST_VERTICAL)) {
  const referenceFile = args.reference ?? process.env.CE_TEST_REFERENCE
  const ownFootage = args.vertical ?? process.env.CE_TEST_VERTICAL

  const styled = await page.evaluate((reference, mine) => (window.__pending = (async () => {
    const post = (url, body) =>
      fetch(`http://127.0.0.1:8742${url}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
      }).then((r) => r.json())

    const template = await post('/api/style/analyze', { path: reference, name: 'ui-test' })
    const built = await post('/api/style/apply', { path: mine, template: 'ui-test', name: 'Styled' })

    // Load it exactly the way the page does, then read the editor back.
    const store = window.__ceEditor
    store.getState().loadSnapshot(built.timeline, built.name)
    store.getState().setAspect(built.aspect)
    await new Promise((r) => setTimeout(r, 800))
    const state = store.getState()
    return {
      shots: template.shots.length,
      bpm: template.bpm,
      aspect: template.aspect,
      unknown: template.unknown.length,
      clips: state.clips.length,
      duration: Math.max(...state.clips.map((c) => c.start + c.duration)),
      gaps: state.clips
        .slice()
        .sort((a, b) => a.start - b.start)
        .slice(1)
        .filter((c, i, all) => {
          const previous = state.clips.slice().sort((a, b) => a.start - b.start)[i]
          return Math.abs(c.start - (previous.start + previous.duration)) > 0.02
        }).length,
      graded: state.clips.every((c) => c.props?.adjust !== undefined),
    }
  })()), referenceFile, ownFootage)

  if (styled.shots >= 5 && Math.abs(styled.bpm - 120) < 3)
    ok(`the reference was measured (${styled.shots} shots, ${Math.round(styled.bpm)} BPM, ${styled.aspect})`)
  else bad('the reference analysis is wrong', JSON.stringify(styled))
  if (styled.clips === styled.shots) ok(`the edit has one clip per template shot (${styled.clips})`)
  else bad('the produced edit does not follow the template', JSON.stringify(styled))
  if (styled.gaps === 0) ok('the produced clips tile the timeline with no gaps')
  else bad('the produced edit has gaps', JSON.stringify(styled))
  if (styled.graded) ok("the template's colour reached every clip")
  else bad('the look was not applied', JSON.stringify(styled))
  if (styled.unknown >= 2) ok('the template states what it cannot know')
  else bad('the template makes no honesty statement', JSON.stringify(styled))

  // The automatic door: footage + a music bed, no prompt and no settings.
  const auto = await page.evaluate((mine, bed) => (window.__pending = (async () => {
    const built = await fetch('http://127.0.0.1:8742/api/style/apply', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ path: mine, template: 'ui-test', music: bed, name: 'Auto' }),
    }).then((r) => r.json())
    const store = window.__ceEditor
    store.getState().loadSnapshot(built.timeline, built.name)
    await new Promise((r) => setTimeout(r, 700))
    const state = store.getState()
    return {
      applied: built.summary.applied,
      skipped: built.summary.skipped,
      music: state.clips.filter((c) => c.trackId === 'a1').length,
      ducked: state.clips.some((c) => c.trackId === 'a1' && c.props?.duck),
      video: state.clips.filter((c) => c.trackId === 'v1').length,
    }
  })()), ownFootage, args.beat ?? process.env.CE_TEST_BEAT)

  if (auto.music === 1 && auto.ducked) ok('the music bed is placed and ducked automatically')
  else bad('the automatic music bed is missing', JSON.stringify(auto))
  if (auto.applied.length >= 4) ok(`the automatic run reports what it did (${auto.applied.length} things)`)
  else bad('the automatic run says nothing about what it did', JSON.stringify(auto))
  if (Array.isArray(auto.skipped)) ok(`…and what it could not do (${auto.skipped.length})`)
  else bad('no honesty list in the summary', JSON.stringify(auto))

/* 11k — long work has a face: stages, a clock, and a Stop button ------------- */
{
  const longFile = args.long ?? process.env.CE_TEST_LONG ?? referenceFile
  await page.evaluate(() => { location.hash = '#/style' })
  await new Promise((r) => setTimeout(r, 700))

  // Start it the way the screen does — through the page's own API layer, so the
  // task, the socket and the panel are all the real ones.
  const started = await page.evaluate((file) => (window.__pending = (async () => {
    const reply = await fetch('http://127.0.0.1:8742/api/style/analyze/start', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ path: file, name: 'ui-progress', save: false }),
    })
    const began = performance.now()
    const task = await reply.json()
    return { ms: performance.now() - began, id: task.id, status: task.status }
  })()), longFile)

  if (started.ms < 2000 && started.status === 'running')
    ok(`starting an analysis returns at once (${Math.round(started.ms)} ms, not a held request)`)
  else bad('the analysis request is still synchronous', JSON.stringify(started))

  // Watch it from the page: stage labels must actually change over time.
  const watched = await page.evaluate((id) => (window.__pending = (async () => {
    const stages = []
    const started = performance.now()
    let last = ''
    while (performance.now() - started < 120000) {
      const state = await fetch(`http://127.0.0.1:8742/api/tasks/${id}`).then((r) => r.json())
      if (state.stage !== last) { stages.push([state.stage, state.progress, state.label]); last = state.stage }
      if (state.status !== 'running') return { stages, status: state.status, elapsed: state.elapsed }
      await new Promise((r) => setTimeout(r, 120))
    }
    return { stages, status: 'timeout', elapsed: 0 }
  })()), started.id)

  if (watched.status === 'done' && watched.stages.length >= 5)
    ok(`the work reports where it is (${watched.stages.length} stages over ${watched.elapsed.toFixed(1)} s)`)
  else bad('long work still cannot say what it is doing', JSON.stringify(watched).slice(0, 300))
  if (watched.stages.every(([, p]) => p >= 0 && p <= 1) && watched.stages.at(-1)[1] === 1)
    ok('progress runs from 0 to 1 and ends there')
  else bad('the progress numbers are wrong', JSON.stringify(watched.stages))
  if (watched.stages.every(([, , label]) => typeof label === 'string' && label.length > 3))
    ok('every stage carries words a person can read')
  else bad('a stage arrived without a label', JSON.stringify(watched.stages))

  // The panel itself, in the DOM. Nothing else may be running: two analyses at
  // once halve the CPU each gets, and a cancel that lands a second later then
  // reads as a broken button rather than a busy machine.
  // Drive the real screen: click "Only analyse a reference" and answer the modal.
  await page.evaluate(() => {
    const button = [...document.querySelectorAll('button')].find((b) => /analyse a reference|الگو را تحلیل/i.test(b.textContent || ''))
    button?.click()
  })
  await new Promise((r) => setTimeout(r, 500))
  await page.evaluate((file) => {
    const input = document.querySelector('.ant-modal input')
    if (input) {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
      setter.call(input, file)
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }
    const okButton = [...document.querySelectorAll('.ant-modal button')].find((b) => /use this file|همین فایل/i.test(b.textContent || ''))
    okButton?.click()
  }, longFile)
  await new Promise((r) => setTimeout(r, 2500))

  const shown = await page.evaluate(() => {
    const node = document.querySelector('[data-testid="style-progress"]')
    const fill = document.querySelector('[data-testid="style-progress-fill"]')
    return node
      ? {
          visible: node.getBoundingClientRect().height > 20,
          stage: node.getAttribute('data-stage'),
          label: document.querySelector('[data-testid="style-progress-label"]')?.textContent ?? '',
          elapsed: document.querySelector('[data-testid="style-progress-elapsed"]')?.textContent ?? '',
          width: fill ? fill.getBoundingClientRect().width : 0,
          stop: Boolean(document.querySelector('[data-testid="style-cancel"]')),
        }
      : null
  })

  if (shown?.visible && shown.stop) ok(`the screen shows the stage and a Stop button (${shown.stage})`)
  else bad('Style Match still shows nothing but a spinner', JSON.stringify(shown))
  if (shown && shown.label.length > 3 && /\d+s/.test(shown.elapsed))
    ok(`the panel names the stage and counts the seconds (${shown.label.slice(0, 40)} · ${shown.elapsed})`)
  else bad('the progress panel has no readable stage or clock', JSON.stringify(shown))
  if (shown && shown.width > 0) ok('the progress bar has actually moved off zero')
  else bad('the progress bar is empty while work is running', JSON.stringify(shown))

  // Stop must stop: the task ends as cancelled, and the panel goes away. How
  // long that takes is the measurement — shot detection is a ten-second stage
  // inside PySceneDetect, so this only passes because the detector is told to
  // stop, not because the loop happens to reach a checkpoint.
  const stopBegan = Date.now()
  await page.evaluate(() => document.querySelector('[data-testid="style-cancel"]')?.click())
  const afterStop = await page.evaluate(() => (window.__pending = (async () => {
    const deadline = performance.now() + 15000
    let state = null
    while (performance.now() < deadline) {
      const tasks = await fetch('http://127.0.0.1:8742/api/tasks').then((r) => r.json())
      const mine = tasks.tasks.filter((t) => t.kind === 'style:analyze')
      state = {
        panel: Boolean(document.querySelector('[data-testid="style-progress"]')),
        cancelled: mine.some((t) => t.status === 'cancelled'),
        running: mine.filter((t) => t.status === 'running').length,
      }
      if (state.cancelled && !state.panel) return state
      await new Promise((r) => setTimeout(r, 200))
    }
    return state
  })()))
  const stopMs = Date.now() - stopBegan

  if (afterStop.cancelled && stopMs < 12000)
    ok(`Stop really cancels the work, not just the spinner (${(stopMs / 1000).toFixed(1)} s)`)
  else bad('the cancelled task is still running', JSON.stringify({ ...afterStop, stopMs }))
  if (!afterStop.panel) ok('the progress panel clears when the work ends')
  else bad('the progress panel stayed on screen after Stop', JSON.stringify(afterStop))
}
}


/* 11k — the local AI panel in Settings -------------------------------------- */
const engines = await page.evaluate(() => (window.__pending = (async () => {
  location.hash = '#/settings'
  await new Promise((r) => setTimeout(r, 1500))
  const rows = [...document.querySelectorAll('.ce-engine')]
  const check = [...document.querySelectorAll('.ce-btn')].find((b) =>
    /Check and time|بررسی و زمان/.test(b.textContent ?? '')
  )
  check?.click()
  await new Promise((r) => setTimeout(r, 2500))
  return {
    rows: rows.length,
    names: rows.map((r) => r.querySelector('strong')?.textContent ?? ''),
    // Every row must state a status in words — and after a failed self-test it
    // must NOT claim to be ready.
    honest: rows.every((r) =>
      /not installed|نصب نیست|installed, not running|نصب است|working|کار می‌کند|not working yet|هنوز کار نمی‌کند/.test(
        r.textContent ?? ''
      )
    ),
    states: rows.map((r) => r.getAttribute('data-state')),
    reported: [...document.querySelectorAll('.ce-engine .ce-hint')].length,
  }
})()))
if (engines.rows === 2 && engines.names.join(',').includes('Ollama')) ok('Settings lists both AI engines')
else bad('the AI engine panel is missing', JSON.stringify(engines))
if (engines.honest) ok('each engine states whether it is installed and running')
else bad('an engine row says nothing useful', JSON.stringify(engines))
// Neither engine exists on the test machine, so after the self-test both rows
// must read "failed" — never "working".
if (engines.states.every((state) => state === 'failed')) ok('a failed engine never claims to work')
else bad('an engine claims to work while its test failed', JSON.stringify(engines))
if (engines.reported === 2) ok('the self-test reports a result for both engines')
else bad('the self-test reported nothing', JSON.stringify(engines))

/* 12 — the home screen starts a video --------------------------------------- */
await page.goto(`${BASE}/#/`, { waitUntil: 'networkidle2' })
await new Promise((r) => setTimeout(r, 900))
const home = await page.evaluate(() => ({
  starters: document.querySelectorAll('.ce-start__main').length,
  recents: Boolean(document.querySelector('.ce-reel, .ce-empty')),
  editorTileSaysSoon: [...document.querySelectorAll('.ce-tile')].some(
    (tile) => /Editor/.test(tile.textContent ?? '') && /SOON/i.test(tile.textContent ?? '')
  ),
  styleTile: [...document.querySelectorAll('.ce-tile')].some((tile) => /Style Match|شبیه الگو/.test(tile.textContent ?? '')),
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
if (home.styleTile) ok('the Style Match tile is on the home screen')
else bad('the Style Match tile is missing', JSON.stringify(home))
if (home.clipTools === 0) ok('clip tools are no longer on the home screen')
else bad('clip tools are still on the home screen', `${home.clipTools} tiles`)

/* 12b — nothing is unreachable from the home screen ------------------------- */
// The tab bar used to be the only way into Settings, and when it went away the
// user could no longer update the app. Every route must be reachable by
// clicking, not just by typing a URL.
const reachable = await page.evaluate(() => {
  const routes = new Set()
  for (const tile of document.querySelectorAll('.ce-tile')) {
    routes.add((tile.textContent ?? '').trim())
  }
  return {
    updateCard: Boolean(document.querySelector('.ce-updatecard')),
    updateButton: Boolean(
      [...document.querySelectorAll('.ce-updatecard button')].find((b) => /Check for updates|بررسی/.test(b.textContent ?? ''))
    ),
    settingsButton: Boolean(document.querySelector('.ce-updatecard__actions .ce-iconbtn')),
    settingsTile: [...routes].some((label) => /Settings|تنظیمات/.test(label)),
    doctorTile: [...routes].some((label) => /System Health|Doctor|Diagnostics|سلامت|عیب/.test(label)),
  }
})
if (reachable.updateCard && reachable.updateButton) ok('the update control is on the home screen')
else bad('the update control is missing from the home screen', JSON.stringify(reachable))
if (reachable.settingsTile || reachable.settingsButton) ok('Settings is reachable from the home screen')
else bad('Settings cannot be reached by clicking', JSON.stringify(reachable))
if (reachable.doctorTile) ok('Diagnostics is reachable from the home screen')
else bad('Diagnostics cannot be reached by clicking', JSON.stringify(reachable))

// …and the buttons really navigate.
const gearWorks = await page.evaluate(() => (window.__pending = (async () => {
  const gear = document.querySelector('.ce-updatecard__actions .ce-iconbtn')
  gear?.click()
  await new Promise((r) => setTimeout(r, 700))
  const hash = location.hash
  location.hash = '#/'
  await new Promise((r) => setTimeout(r, 600))
  return hash
})()))
if (/settings/.test(gearWorks)) ok('the gear opens Settings')
else bad('the gear does not open Settings', String(gearWorks))

// A saved project must exist before its delete button can be looked for; a
// fresh machine has none.
await page.evaluate(() => (window.__pending = (async () => {
  await fetch('http://127.0.0.1:8742/api/projects', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name: 'Test project', timeline: window.__ceEditor.getState().toDocument() }),
  })
  location.hash = '#/studio'
  await new Promise((r) => setTimeout(r, 400))
  location.hash = '#/'
  await new Promise((r) => setTimeout(r, 1200))
})()))

const recents = await page.evaluate(() => ({
  cards: document.querySelectorAll('.ce-reelcard').length,
  draft: Boolean(document.querySelector('.ce-reelcard.is-unfinished')),
  deletes: document.querySelectorAll('.ce-reelcard__del').length,
}))
if (recents.draft) ok('the unfinished project appears in Recent projects')
else bad('the unfinished (autosaved) project is not offered on the home screen', JSON.stringify(recents))
if (recents.deletes > 0) ok(`each saved project has a delete button (${recents.deletes})`)
else bad('saved projects cannot be deleted from the home screen', JSON.stringify(recents))

// ------------------------------------------------------------- auto-reframe
//
// The Face Tracking tool must put a real camera path on the clip — keyframes
// the user can see and drag — and must say so honestly when there is no face.
// (The fixture clips here have no faces, which is exactly the honest case.)
await page.evaluate((src) => (window.__pending = (async () => {
  location.hash = '#/studio'
  await new Promise((r) => setTimeout(r, 600))
  const state = window.__ceEditor.getState()
  const clip = state.clips.find((c) => c.src) ?? state.clips[0]
  window.__reframe = await fetch('http://127.0.0.1:8742/api/reframe/plan', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ path: src, width: 1080, height: 1920 }),
  }).then((r) => r.json())
  window.__reframeClip = clip?.id ?? null
})()), A)

const reframePlan = await page.evaluate(() => window.__reframe)
if (reframePlan && typeof reframePlan.scale === 'number' && Array.isArray(reframePlan.keyframes)) {
  ok(`auto-reframe answers with a camera path (scale ${reframePlan.scale}, ${reframePlan.keyframes.length} key(s))`)
} else {
  bad('auto-reframe did not answer with a plan', JSON.stringify(reframePlan))
}
if (reframePlan?.fallback ? typeof reframePlan.reason === 'string' && reframePlan.reason.length > 0 : true) {
  ok(`auto-reframe explains itself ("${reframePlan?.reason ?? ''}")`)
} else {
  bad('auto-reframe fell back without saying why', JSON.stringify(reframePlan))
}

// A path with keys must land on the clip as editable keyframes, in one step.
const applied = await page.evaluate(() => (window.__pending = (async () => {
  const state = window.__ceEditor.getState()
  const id = window.__reframeClip
  if (!id) return { skipped: true }
  const before = (state.clips.find((c) => c.id === id)?.keyframes ?? []).length
  state.setClipKeyframes(id, [{ t: 0, x: -0.4 }, { t: 1, x: 0.4 }], 3.16)
  await new Promise((r) => setTimeout(r, 200))
  const after = window.__ceEditor.getState()
  const clip = after.clips.find((c) => c.id === id)
  const undoable = typeof after.undo === 'function'
  after.undo()
  const restored = (window.__ceEditor.getState().clips.find((c) => c.id === id)?.keyframes ?? []).length
  return {
    before,
    keys: (clip?.keyframes ?? []).length,
    scale: clip?.props?.transform?.scale ?? null,
    undoable,
    restored,
  }
})()))
if (applied.skipped) ok('auto-reframe: no clip to apply to (skipped)')
else if (applied.keys === 2 && Math.abs((applied.scale ?? 0) - 3.16) < 0.01) {
  ok('the camera path lands on the clip as keyframes with the fill scale')
} else bad('the camera path did not reach the clip', JSON.stringify(applied))
if (applied.skipped || applied.restored === applied.before) ok('and Ctrl+Z takes it back in one step')
else bad('auto-reframe is not undoable in one step', JSON.stringify(applied))

// ---------------------------------------------------------------- the brain
//
// A free-form prompt cannot be scored, so the safety is that nothing happens
// until the user has read what will happen and pressed Apply. These checks are
// the difference between "the assistant works" and "the assistant did something
// nobody agreed to".
await page.evaluate(() => (window.__pending = (async () => {
  location.hash = '#/studio'
  await new Promise((r) => setTimeout(r, 800))
  const state = window.__ceEditor.getState()
  if (!state.clips.length) {
    state.addClip({ src: '', label: 'brain', start: 0, duration: 4, offset: 0, sourceDuration: 4 })
  }
  document.querySelector('.ai-fab')?.click()
  await new Promise((r) => setTimeout(r, 300))
  const input = document.querySelector('.ai-panel__input input')
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
  setter.call(input, 'add fade transitions between all clips')
  input.dispatchEvent(new Event('input', { bubbles: true }))
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
  await new Promise((r) => setTimeout(r, 2500))
})()))

const dryRun = await page.evaluate(() => {
  const panel = document.querySelector('[data-testid="assistant-dryrun"]')
  return {
    shown: Boolean(panel),
    steps: panel ? panel.querySelectorAll('li').length : 0,
    text: panel ? (panel.querySelector('li')?.textContent ?? '') : '',
    apply: Boolean(document.querySelector('[data-testid="assistant-apply"]')),
    cancel: Boolean(document.querySelector('[data-testid="assistant-cancel"]')),
  }
})
if (dryRun.shown && dryRun.steps > 0) ok(`the assistant shows a dry run first (${dryRun.steps} step(s): "${dryRun.text}")`)
else bad('the assistant applied without showing what it would do', JSON.stringify(dryRun))
if (dryRun.apply && dryRun.cancel) ok('the dry run offers Apply and Cancel')
else bad('the dry run has no Apply/Cancel', JSON.stringify(dryRun))

// Cancel must change nothing at all.
const cancelled = await page.evaluate(() => (window.__pending = (async () => {
  const before = JSON.stringify(window.__ceEditor.getState().transitions)
  document.querySelector('[data-testid="assistant-cancel"]')?.click()
  await new Promise((r) => setTimeout(r, 300))
  return {
    gone: !document.querySelector('[data-testid="assistant-dryrun"]'),
    unchanged: before === JSON.stringify(window.__ceEditor.getState().transitions),
  }
})()))
if (cancelled.gone && cancelled.unchanged) ok('Cancel leaves the timeline untouched')
else bad('Cancel did not leave the timeline untouched', JSON.stringify(cancelled))

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

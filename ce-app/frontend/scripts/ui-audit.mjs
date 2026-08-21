#!/usr/bin/env node
/**
 * Headless UI audit for Cutting Edge.
 *
 * Walks every route in a running dev server and fails on the classes of bug that
 * have actually reached users in this project:
 *   • a screen that renders nothing (the black-window class of failure)
 *   • overlapping layout boxes
 *   • horizontal overflow
 *   • console/page errors
 *   • the language switch not flipping direction or not persisting
 *
 * Usage:
 *   npm run dev            # in ce-app/frontend, in another terminal
 *   npm run test:ui        # or: node scripts/ui-audit.mjs --url http://127.0.0.1:5173
 *
 * Any Chromium works: set CHROME_PATH, or install one with
 *   npx playwright install chromium
 * (the script picks up PLAYWRIGHT_BROWSERS_PATH too).
 */
import { existsSync } from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)

const args = Object.fromEntries(
  process.argv.slice(2).map((a, i, all) => (a.startsWith('--') ? [a.slice(2), all[i + 1] ?? true] : []))
)
const BASE = args.url ?? process.env.CE_UI_URL ?? 'http://127.0.0.1:5173'
const ROUTES = ['', '#/dashboard', '#/studio', '#/new', '#/uploads', '#/settings', '#/doctor']

function findChrome() {
  if (process.env.CHROME_PATH && existsSync(process.env.CHROME_PATH)) return process.env.CHROME_PATH
  const candidates = [
    '/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome',
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  ]
  return candidates.find((p) => existsSync(p))
}

let puppeteer
try {
  puppeteer = require('puppeteer-core')
} catch {
  console.error('puppeteer-core is required:  npm i -D puppeteer-core')
  process.exit(2)
}

const executablePath = findChrome()
if (!executablePath) {
  console.error('No Chromium found. Set CHROME_PATH, or run: npx playwright install chromium')
  process.exit(2)
}

const failures = []
const note = (route, message) => failures.push(`${route || '/'} → ${message}`)

const browser = await puppeteer.launch({
  executablePath,
  headless: 'shell',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
  defaultViewport: { width: 1180, height: 900 },
})
const page = await browser.newPage()

const errors = []
page.on('pageerror', (e) => errors.push(String(e).slice(0, 200)))
page.on('console', (m) => {
  if (m.type() !== 'error') return
  const text = m.text()
  // the dev server has no backend attached in CI; ignore only that noise
  if (/Failed to load resource|WebSocket connection/.test(text)) return
  errors.push(text.slice(0, 200))
})

const measure = () =>
  page.evaluate(() => {
    const de = document.documentElement
    const boxes = [...document.querySelectorAll('.ce-card, .ce-stat, .ed__toolbar, .tl, .ce-page__head')]
      .map((e) => e.getBoundingClientRect())
    let overlaps = 0
    for (let i = 0; i < boxes.length; i++) {
      for (let j = i + 1; j < boxes.length; j++) {
        const a = boxes[i]
        const b = boxes[j]
        const inter =
          Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left)) *
          Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top))
        const contains =
          (a.top <= b.top && a.bottom >= b.bottom && a.left <= b.left && a.right >= b.right) ||
          (b.top <= a.top && b.bottom >= a.bottom && b.left <= a.left && b.right >= a.right)
        if (inter > 40 && !contains) overlaps++
      }
    }
    return {
      overflowX: de.scrollWidth > de.clientWidth,
      overlaps,
      rendered: document.body.innerText.trim().length > 40,
      mounted: document.querySelectorAll('.ce-route').length,
    }
  })

for (const route of ROUTES) {
  await page.goto(`${BASE}/${route}`, { waitUntil: 'networkidle0', timeout: 60_000 })
  await new Promise((r) => setTimeout(r, 700))
  const m = await measure()
  if (!m.rendered) note(route, 'screen rendered nothing')
  if (m.overlaps > 0) note(route, `${m.overlaps} overlapping layout boxes`)
  if (m.overflowX) note(route, 'horizontal overflow')
  if (m.mounted !== 1) note(route, `${m.mounted} route subtrees mounted (expected 1)`)
  console.log(
    `${(route || '/').padEnd(14)} rendered=${m.rendered} overlaps=${m.overlaps} overflowX=${m.overflowX}`
  )
}

// rapid navigation must not stack screens or inherit scroll
await page.goto(BASE, { waitUntil: 'networkidle0' })
for (const hash of ['#/dashboard', '#/studio', '#/settings', '#/', '#/new', '#/dashboard', '#/']) {
  await page.evaluate((h) => { location.hash = h }, hash)
  await new Promise((r) => setTimeout(r, 120))
}
await new Promise((r) => setTimeout(r, 700))
const after = await page.evaluate(() => ({
  mounted: document.querySelectorAll('.ce-route').length,
  headers: document.querySelectorAll('.ce-header').length,
  scrollTop: document.querySelector('.ce-content')?.scrollTop ?? 0,
}))
if (after.mounted !== 1) note('rapid-switch', `${after.mounted} screens mounted`)
if (after.headers !== 1) note('rapid-switch', `${after.headers} headers mounted`)
if (after.scrollTop !== 0) note('rapid-switch', 'scroll position leaked between screens')
console.log(`rapid-switch   mounted=${after.mounted} headers=${after.headers} scrollTop=${after.scrollTop}`)

// editor invariants: empty start, no overlapping clips, zoom control present
await page.goto(`${BASE}/#/studio`, { waitUntil: 'networkidle0' })
await new Promise((r) => setTimeout(r, 900))
const editor = await page.evaluate(() =>
  import('/src/editor/model.ts').then((m) => {
    const s = m.useEditor.getState()
    s.clearTimeline()
    const a = s.addClip({ trackId: 'v1', start: 0, duration: 4, offset: 0, sourceDuration: 10, src: 'x', label: 'A', color: '#111' })
    const b = s.addClip({ trackId: 'v1', start: 6, duration: 4, offset: 0, sourceDuration: 10, src: 'x', label: 'B', color: '#222' })
    m.useEditor.getState().moveClip(b, 1) // aim straight at A
    const clips = m.useEditor.getState().clips
    const first = clips.find((c) => c.id === a)
    const second = clips.find((c) => c.id === b)
    const overlapping =
      second.start < first.start + first.duration && second.start + second.duration > first.start
    return { overlapping, zoomBar: !!document.querySelector('.tl__zoombar') }
  })
)
if (editor.overlapping) note('editor', 'clips are allowed to overlap on a lane')
if (!editor.zoomBar) note('editor', 'timeline zoom control missing')
console.log(`editor         overlapping=${editor.overlapping} zoomBar=${editor.zoomBar}`)

// language switch: direction flips and the choice persists
await page.goto(`${BASE}/#/settings`, { waitUntil: 'networkidle0' })
await new Promise((r) => setTimeout(r, 600))
const before = await page.evaluate(() => document.documentElement.dir)
await page.evaluate(() => {
  const button = [...document.querySelectorAll('.ce-langbtn')].find((b) => b.textContent.includes('فارسی'))
  button?.click()
})
await new Promise((r) => setTimeout(r, 600))
const flipped = await page.evaluate(() => ({
  dir: document.documentElement.dir,
  saved: localStorage.getItem('ce.lang'),
}))
if (!(before === 'ltr' && flipped.dir === 'rtl')) note('i18n', `direction did not flip (${before} → ${flipped.dir})`)
if (flipped.saved !== 'fa') note('i18n', 'language choice not persisted')
console.log(`i18n           ${before} → ${flipped.dir}, persisted=${flipped.saved}`)

if (errors.length) failures.push(...errors.map((e) => `console → ${e}`))
await browser.close()

console.log('')
if (failures.length) {
  console.error(`UI AUDIT FAILED (${failures.length})`)
  failures.forEach((f) => console.error('  • ' + f))
  process.exit(1)
}
console.log('UI AUDIT PASSED')

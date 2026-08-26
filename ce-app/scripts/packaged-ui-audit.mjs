#!/usr/bin/env node
/**
 * UI audit of the PACKAGED app, on the Windows runner.
 *
 * smoke-test.ps1 proves the packaged app *starts*; this proves its window is
 * not a black box: it launches the real `Cutting Edge.exe` with Electron's
 * remote-debugging port open, attaches over CDP with puppeteer-core, and runs
 * the same classes of check ui-audit.mjs runs on the dev server — the screen
 * renders text, no console/page errors, no horizontal overflow. A packaged
 * build whose renderer died used to look exactly like an empty app (§8); this
 * is the tripwire for that, at the artefact level, where it matters.
 *
 * Usage (Windows runner):
 *   node ce-app/scripts/packaged-ui-audit.mjs --exe "ce-app/frontend/release/win-unpacked/Cutting Edge.exe"
 */
import { spawn } from 'node:child_process'
import { setTimeout as sleep } from 'node:timers/promises'

const exeArg = process.argv.indexOf('--exe')
const exe = exeArg >= 0 ? process.argv[exeArg + 1] : null
if (!exe) {
  console.error('usage: packaged-ui-audit.mjs --exe <path to Cutting Edge.exe>')
  process.exit(2)
}

const PORT = 9333
const { createRequire } = await import('node:module')
const require = createRequire(new URL('../frontend/package.json', import.meta.url))
const puppeteer = require('puppeteer-core')

const child = spawn(exe, [`--remote-debugging-port=${PORT}`, '--no-sandbox'], {
  stdio: 'ignore',
  detached: false,
})

const fail = async (why) => {
  console.error(`PACKAGED UI AUDIT FAILED: ${why}`)
  try { child.kill() } catch { /* already gone */ }
  process.exit(1)
}

let browser
try {
  // Electron needs a beat to open the debugging port and load the renderer.
  let page = null
  for (let attempt = 0; attempt < 30 && !page; attempt++) {
    await sleep(2000)
    try {
      browser = await puppeteer.connect({ browserURL: `http://127.0.0.1:${PORT}` })
      const pages = await browser.pages()
      page = pages.find((p) => p.url().startsWith('file://')) ?? pages[0] ?? null
    } catch { /* port not up yet */ }
  }
  if (!page) await fail('no renderer window answered the debugging port')

  const errors = []
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()) })
  await sleep(4000) // let the home screen mount and poll the backend

  const report = await page.evaluate(() => ({
    text: document.body ? document.body.innerText.length : 0,
    overflowX: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
  }))

  if (report.text < 200) await fail(`the packaged window rendered ${report.text} chars of text`)
  if (report.overflowX) await fail('horizontal overflow in the packaged window')
  if (errors.length) await fail(errors.slice(0, 5).join(' | '))

  console.log(`PACKAGED UI AUDIT PASSED — ${report.text} chars rendered, no errors`)
} finally {
  try { browser?.disconnect() } catch { /* fine */ }
  try { child.kill() } catch { /* fine */ }
}

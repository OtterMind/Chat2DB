#!/usr/bin/env node
/**
 * Contract check between the renderer and the Electron preload bridge.
 *
 * The import button shipped broken because `pickMedia` was called on
 * `window.cuttingEdge` but never exposed in preload.ts. Nothing failed loudly:
 * the call fell through to a browser fallback that Electron blocks, the promise
 * rejected, and the UI stayed completely silent.
 *
 * This script fails the build when:
 *   • the renderer uses a bridge method preload does not expose
 *   • preload forwards an IPC channel the main process never handles
 *
 * Usage:  node scripts/check-bridge.mjs      (also part of `npm run verify`)
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

const root = new URL('..', import.meta.url).pathname
const read = (p) => readFileSync(join(root, p), 'utf8')

function walk(dir, out = []) {
  for (const entry of readdirSync(join(root, dir))) {
    const rel = `${dir}/${entry}`
    if (statSync(join(root, rel)).isDirectory()) walk(rel, out)
    else if (/\.(ts|tsx)$/.test(entry)) out.push(rel)
  }
  return out
}

const preload = read('electron/preload.ts')
const main = read('electron/main.ts')
const updater = read('electron/updater.ts')

// 1. what preload exposes
const exposed = new Set(
  [...preload.matchAll(/^\s{2}([a-zA-Z][\w]*)\s*:/gm)].map((m) => m[1]).filter((n) => n !== 'versions')
)

// 2. what the renderer expects from the bridge
const used = new Set()
for (const file of walk('src')) {
  const source = read(file)
  for (const m of source.matchAll(/cuttingEdge\??\.\s*([a-zA-Z]\w*)/g)) used.add(m[1])
  // typed wrappers: `bridge.foo(` / `bridge?.foo?.(`
  for (const m of source.matchAll(/\bbridge\??\.\s*([a-zA-Z]\w*)\??\s*\(/g)) used.add(m[1])
  // interface members of the bridge wrapper
  if (file.endsWith('services/updater.ts')) {
    const block = source.match(/interface Bridge \{([\s\S]*?)\}/)
    if (block) for (const m of block[1].matchAll(/^\s*([a-zA-Z]\w*)\s*:/gm)) used.add(m[1])
  }
}

// 3. channels preload talks to, and channels main answers
const invoked = new Set(
  [...preload.matchAll(/ipcRenderer\.(?:invoke|send)\(\s*'([^']+)'/g)].map((m) => m[1])
)
const handled = new Set(
  [...(main + updater).matchAll(/ipcMain\.(?:handle|on)\(\s*'([^']+)'/g)].map((m) => m[1])
)

const problems = []
for (const name of used) {
  if (!exposed.has(name)) problems.push(`renderer calls cuttingEdge.${name}() but preload does not expose it`)
}
for (const channel of invoked) {
  if (!handled.has(channel)) problems.push(`preload sends '${channel}' but no ipcMain handler exists`)
}

console.log(`bridge: ${exposed.size} exposed · ${used.size} used by the renderer · ${invoked.size} channels`)
if (problems.length) {
  console.error('\nBRIDGE CONTRACT BROKEN')
  problems.forEach((p) => console.error('  • ' + p))
  process.exit(1)
}
console.log('bridge contract OK')

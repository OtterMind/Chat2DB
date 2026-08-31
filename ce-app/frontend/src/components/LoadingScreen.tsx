import { useEffect, useRef, useState } from 'react'
import * as THREE from 'three'

/**
 * The cold-start loading screen: a **big bang**.
 *
 * A single point holds for a beat, detonates, and the debris spreads out like
 * stars and planets before settling into the constellation the launcher keeps.
 * It is the design the owner drew (`docs/CuttingEdge/ui-proposal/
 * loadscreen-frame1.png`, `loadscreen-frame2.png`), implemented for real.
 *
 * Three deliberate engineering choices:
 *
 * 1. **Plain `three`, not `@react-three/fiber` + `drei` + `gsap`.** The mockup
 *    named those, but `three` is already a dependency (the live globe), and a
 *    React reconciler plus two animation libraries would add ~150 KB to a
 *    bundle this app measures. One imperative scene does the same job.
 * 2. **The ring reports real work, not a timer.** It advances on the steps that
 *    actually gate the app — the backend answering, the projects loading, the
 *    motion package arriving — so a slow machine shows a slow ring instead of a
 *    lie that finishes before the app is ready.
 * 3. **`prefers-reduced-motion` gets the settled frame, not the explosion.** No
 *    flashes, no travel; the galaxy is drawn once and the screen steps aside.
 *
 * The active **motion package** drives it: `particles` sizes the debris field,
 * `duration` scales the whole sequence, `ease` shapes the settle.
 */

const NODES = [
  { name: 'MySQL', color: 0x22c55e, angle: 0.4 },
  { name: 'Postgres', color: 0xfbbf24, angle: 1.7 },
  { name: 'MongoDB', color: 0x3b82f6, angle: 2.9 },
  { name: 'Redis', color: 0xef4444, angle: 4.2 },
  { name: 'SQLite', color: 0xf97316, angle: 5.4 },
]

const STATUS_LINES = [
  ['Waking the engine', 'بیدارکردن موتور'],
  ['Reading your projects', 'خواندن پروژه‌ها'],
  ['Tuning the motion language', 'تنظیم زبان موشن'],
  ['Ready', 'آماده'],
]

/** Solve a CSS `cubic-bezier(...)`/keyword into an easing function. */
function makeEase(css?: string): (t: number) => number {
  const spec = (css ?? '').trim()
  let c: [number, number, number, number] | undefined
  if (spec.startsWith('cubic-bezier(')) {
    const p = spec.slice(13, -1).split(',').map((n) => Number(n.trim()))
    if (p.length === 4 && p.every((n) => Number.isFinite(n))) c = p as [number, number, number, number]
  } else if (spec === 'linear') c = [0, 0, 1, 1]
  else if (spec === 'ease') c = [0.25, 0.1, 0.25, 1]
  else if (spec === 'ease-in') c = [0.42, 0, 1, 1]
  else if (spec === 'ease-out') c = [0, 0, 0.58, 1]
  else if (spec === 'ease-in-out') c = [0.42, 0, 0.58, 1]
  if (!c) return (t) => t
  const [x1, y1, x2, y2] = c
  const bez = (a: number, b: number, t: number) =>
    3 * a * (1 - t) ** 2 * t + 3 * b * (1 - t) * t ** 2 + t ** 3
  return (x: number) => {
    let t = x
    for (let i = 0; i < 5; i += 1) {
      const slope = 3 * x1 * (1 - t) ** 2 + 6 * (x2 - x1) * (1 - t) * t + 3 * (1 - x2) * t ** 2
      if (Math.abs(slope) < 1e-6) break
      t -= (bez(x1, x2, t) - x) / slope
    }
    return bez(y1, y2, Math.min(1, Math.max(0, t)))
  }
}

export interface BootStep {
  /** 0..1 — how much of this step is done. */
  progress: number
  /** Which line of `STATUS_LINES` to type. */
  stage: number
}

export default function LoadingScreen({
  onDone,
  boot,
  lang,
}: {
  onDone: () => void
  boot: BootStep
  lang: string
}) {
  const mount = useRef<HTMLDivElement>(null)
  const [typed, setTyped] = useState('')
  const [shown, setShown] = useState(0)
  const reduce =
    typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches

  /**
   * Two honesty rules for the ring and the narration.
   *
   * The ring **never shows more than the truth** — `shown` eases up towards the
   * real progress and stops there. On a fast machine the three boot requests
   * land inside one frame; without this the ring would snap 0 → 100 % and the
   * screen would be a flash, which is exactly the "nothing animated" complaint.
   *
   * The narration **never claims a stage that has not happened** — it walks the
   * lines in order, but only up to the stage the boot really reached, and it
   * holds each line long enough to be read.
   */
  useEffect(() => {
    if (reduce) { setShown(boot.progress); return }
    let raf = 0
    let last = performance.now()
    const tick = (now: number) => {
      const dt = Math.min(0.1, (now - last) / 1000)
      last = now
      setShown((prev) => {
        const next = Math.min(boot.progress, prev + dt * 0.62)
        return boot.progress - next < 0.004 ? boot.progress : next
      })
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [boot.progress, reduce])

  const [line, setLine] = useState(0)
  useEffect(() => {
    if (reduce) { setLine(Math.min(boot.stage, STATUS_LINES.length - 1)); return }
    const ceiling = boot.progress >= 1
      ? STATUS_LINES.length - 1
      : Math.min(boot.stage, STATUS_LINES.length - 2)
    // One line at a time, on a clock: advancing inside the effect body raced to
    // the last line in a single render, so the narration only ever said "Ready".
    const id = window.setInterval(() => {
      setLine((prev) => (prev >= ceiling ? prev : prev + 1))
    }, 520)
    return () => window.clearInterval(id)
  }, [boot.stage, boot.progress, reduce])

  /* ---- the typewriter ---------------------------------------------------- */
  useEffect(() => {
    const entry = STATUS_LINES[Math.min(line, STATUS_LINES.length - 1)]
    const full = lang === 'fa' ? entry[1] : entry[0]
    if (reduce) { setTyped(full); return }
    let i = 0
    setTyped('')
    const id = window.setInterval(() => {
      i += 1
      setTyped(full.slice(0, i))
      if (i >= full.length) window.clearInterval(id)
    }, 26)
    return () => window.clearInterval(id)
  }, [line, lang, reduce])

  /* ---- the scene ---------------------------------------------------------- */
  useEffect(() => {
    const el = mount.current
    if (!el) return
    const pkg = (window as any).__ceMotion ?? {}
    const count = Math.min(3000, Math.max(400, Math.round((pkg.particles ?? 8) * 180)))
    const timeScale = 1 / (pkg.duration || 1)
    const ease = makeEase(pkg.ease)

    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(50, el.clientWidth / el.clientHeight, 0.1, 200)
    camera.position.z = 9
    const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true })
    renderer.setSize(el.clientWidth, el.clientHeight)
    renderer.setPixelRatio(Math.min(2, window.devicePixelRatio))
    el.appendChild(renderer.domElement)

    /* Debris: one buffer, allocated once. Position 0 is the singularity. */
    const home = new Float32Array(count * 3)
    const vel = new Float32Array(count * 3)
    const pos = new Float32Array(count * 3)
    const colors = new Float32Array(count * 3)
    const cyan = new THREE.Color(0x00f0ff)
    const violet = new THREE.Color(0x8b5cf6)
    const pink = new THREE.Color(0xff2d9c)
    for (let i = 0; i < count; i += 1) {
      const v = new THREE.Vector3().randomDirection()
      // 80 % become a flat galaxy disc, the rest stay a halo — a disc reads as
      // a system, a sphere reads as smoke.
      if (i % 5 !== 0) v.multiplyScalar(0.35 + Math.random() * 0.65).setZ(v.z * 0.18)
      const radius = 2.2 + Math.random() * 4.6
      home[i * 3] = v.x * radius
      home[i * 3 + 1] = v.y * radius
      home[i * 3 + 2] = v.z * radius
      // The bang throws everything outward; the far ones fly fastest.
      const speed = 5 + Math.random() * 16
      vel[i * 3] = v.x * speed
      vel[i * 3 + 1] = v.y * speed
      vel[i * 3 + 2] = v.z * speed
      const tint = cyan.clone().lerp(Math.random() < 0.3 ? pink : violet, Math.random())
      colors[i * 3] = tint.r; colors[i * 3 + 1] = tint.g; colors[i * 3 + 2] = tint.b
    }
    const geo = new THREE.BufferGeometry()
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3))
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3))
    const debris = new THREE.Points(geo, new THREE.PointsMaterial({
      size: 0.045, vertexColors: true, transparent: true, opacity: 0,
      blending: THREE.AdditiveBlending, depthWrite: false,
    }))
    scene.add(debris)

    /* The singularity: one sprite that swells, then is gone. */
    const core = new THREE.Mesh(
      new THREE.SphereGeometry(0.16, 24, 24),
      new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 1 }),
    )
    scene.add(core)

    /* The planets the debris leaves behind. */
    const planets = NODES.map((n, i) => {
      const mesh = new THREE.Mesh(
        new THREE.SphereGeometry(0.13 + i * 0.015, 20, 20),
        new THREE.MeshBasicMaterial({ color: n.color, transparent: true, opacity: 0 }),
      )
      scene.add(mesh)
      return mesh
    })

    /* The arcs between them, with a pulse running along each. */
    const pulses: { mesh: THREE.Mesh; from: number; to: number; t: number }[] = []
    for (let i = 0; i < NODES.length; i += 1) {
      const m = new THREE.Mesh(new THREE.SphereGeometry(0.045, 10, 10),
        new THREE.MeshBasicMaterial({ color: 0x00f0ff, transparent: true, opacity: 0 }))
      scene.add(m)
      pulses.push({ mesh: m, from: i, to: (i + 1) % NODES.length, t: i * 0.2 })
    }

    const nodePos = (i: number, spin: number) => {
      const a = NODES[i].angle + spin
      const r = 2.5
      return new THREE.Vector3(Math.cos(a) * r, Math.sin(a) * r * 0.55, Math.sin(a) * 0.8)
    }

    /* Reduced motion: draw the settled frame once and stop. */
    if (reduce) {
      for (let i = 0; i < count; i += 1) {
        pos[i * 3] = home[i * 3]; pos[i * 3 + 1] = home[i * 3 + 1]; pos[i * 3 + 2] = home[i * 3 + 2]
      }
      geo.attributes.position.needsUpdate = true
      ;(debris.material as THREE.PointsMaterial).opacity = 0.75
      core.visible = false
      planets.forEach((p, i) => { p.position.copy(nodePos(i, 0)); (p.material as THREE.MeshBasicMaterial).opacity = 0.9 })
      renderer.render(scene, camera)
      const onResize = () => {
        renderer.setSize(el.clientWidth, el.clientHeight)
        camera.aspect = el.clientWidth / el.clientHeight
        camera.updateProjectionMatrix()
        renderer.render(scene, camera)
      }
      window.addEventListener('resize', onResize)
      return () => { window.removeEventListener('resize', onResize); renderer.dispose(); el.removeChild(renderer.domElement) }
    }

    const BANG = 0.42          // s — how long the singularity holds
    const FLY = 1.05           // s — the explosion
    const SETTLE = 1.15        // s — debris finds its orbit
    const TOTAL = (BANG + FLY + SETTLE) * (1 / timeScale)
    const start = performance.now()
    let raf = 0

    const tick = () => {
      const t = (performance.now() - start) / 1000
      const scaled = t * timeScale

      if (scaled < BANG) {
        // Hold: the point breathes and swells.
        const k = scaled / BANG
        core.scale.setScalar(0.6 + k * 2.4)
        ;(core.material as THREE.MeshBasicMaterial).opacity = 0.7 + 0.3 * Math.sin(k * 18)
        ;(debris.material as THREE.PointsMaterial).opacity = 0
      } else {
        core.visible = false
        const fly = Math.min(1, (scaled - BANG) / FLY)
        const settle = ease(Math.min(1, Math.max(0, (scaled - BANG - FLY * 0.35) / SETTLE)))
        const mat = debris.material as THREE.PointsMaterial
        mat.opacity = Math.min(0.9, fly * 1.4)
        for (let i = 0; i < count; i += 1) {
          // outward flight, then an eased pull back to the settled position
          const drag = fly * (1 - settle)
          pos[i * 3] = vel[i * 3] * drag + home[i * 3] * settle
          pos[i * 3 + 1] = vel[i * 3 + 1] * drag + home[i * 3 + 1] * settle
          pos[i * 3 + 2] = vel[i * 3 + 2] * drag + home[i * 3 + 2] * settle
        }
        geo.attributes.position.needsUpdate = true

        const spin = (scaled - BANG) * 0.22
        debris.rotation.z = spin * 0.25
        planets.forEach((p, i) => {
          const v = nodePos(i, spin * 0.4)
          p.position.copy(v)
          ;(p.material as THREE.MeshBasicMaterial).opacity = settle * 0.95
        })
        for (const p of pulses) {
          p.t = (p.t + 0.006 * timeScale) % 1
          const a = nodePos(p.from, spin * 0.4)
          const b = nodePos(p.to, spin * 0.4)
          p.mesh.position.lerpVectors(a, b, ease(p.t))
          ;(p.mesh.material as THREE.MeshBasicMaterial).opacity = settle * 0.85 * Math.sin(Math.PI * p.t)
        }
      }

      camera.position.z = 9 - Math.min(1.4, scaled * 0.5)
      renderer.render(scene, camera)
      if (t < TOTAL + 0.6) raf = requestAnimationFrame(tick)
    }
    tick()

    const onResize = () => {
      renderer.setSize(el.clientWidth, el.clientHeight)
      camera.aspect = el.clientWidth / el.clientHeight
      camera.updateProjectionMatrix()
    }
    window.addEventListener('resize', onResize)
    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', onResize)
      renderer.dispose()
      el.removeChild(renderer.domElement)
    }
  }, [reduce])

  const pct = Math.round(Math.min(1, Math.max(0, shown)) * 100)
  const R = 46
  const circ = 2 * Math.PI * R

  return (
    <div className="ld-screen" role="status" aria-live="polite" aria-label="Cutting Edge">
      <div className="ld-screen__gl" ref={mount} aria-hidden />
      <div className="ld-screen__ui">
        <svg className="ld-ring" width="112" height="112" viewBox="0 0 112 112" aria-hidden>
          <circle cx="56" cy="56" r={R} className="ld-ring__track" />
          <circle
            cx="56" cy="56" r={R} className="ld-ring__bar"
            strokeDasharray={circ}
            strokeDashoffset={circ * (1 - Math.min(1, Math.max(0, shown)))}
          />
        </svg>
        <div className="ld-screen__pct mono">{pct}%</div>
        <div className="ld-screen__status" data-testid="ld-status">
          {typed}
          <span className="ld-screen__caret" aria-hidden />
        </div>
      </div>
      <button className="ld-screen__skip" onClick={onDone} type="button">
        {lang === 'fa' ? 'رد کردن' : 'Skip'}
      </button>
    </div>
  )
}

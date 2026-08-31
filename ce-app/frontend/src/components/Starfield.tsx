import { useEffect, useRef } from 'react'

/**
 * The live background: a slow starfield with a few drifting planets.
 *
 * It is deliberately a **2D canvas**, not WebGL. The editor already owns the GPU
 * (real-time preview, film strips, waveforms) and this has to sit behind every
 * screen including that one, so it costs a couple of hundred `fillRect`s a frame
 * instead of a second GL context. No allocations happen inside the frame loop.
 *
 * The active motion package drives it (`particles` → how many stars,
 * `duration` → how fast they drift) and `prefers-reduced-motion` gets one static
 * frame. It stops entirely when the tab is hidden.
 */
export default function Starfield({ dim = 1 }: { dim?: number }) {
  const ref = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = ref.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const read = () => {
      const m = (window as any).__ceMotion ?? {}
      return {
        count: Math.min(420, Math.max(60, Math.round((m.particles ?? 8) * 22))),
        speed: 1 / (m.duration || 1),
      }
    }
    let cfg = read()

    const TINTS = ['0,240,255', '139,92,246', '255,45,156', '255,255,255']
    let w = 0
    let h = 0
    let stars: { x: number; y: number; z: number; r: number; c: string }[] = []
    let planets: { x: number; y: number; r: number; vx: number; vy: number; c: string }[] = []

    const seed = () => {
      w = canvas.clientWidth
      h = canvas.clientHeight
      canvas.width = Math.max(1, Math.round(w * Math.min(2, window.devicePixelRatio)))
      canvas.height = Math.max(1, Math.round(h * Math.min(2, window.devicePixelRatio)))
      ctx.setTransform(canvas.width / w, 0, 0, canvas.height / h, 0, 0)
      stars = new Array(cfg.count).fill(0).map(() => ({
        x: Math.random() * w,
        y: Math.random() * h,
        z: 0.25 + Math.random() * 0.75,          // depth = parallax + brightness
        r: 0.4 + Math.random() * 1.5,
        c: TINTS[Math.floor(Math.random() * TINTS.length)],
      }))
      planets = new Array(4).fill(0).map((_, i) => ({
        x: Math.random() * w,
        y: Math.random() * h,
        r: 26 + i * 14,
        vx: (Math.random() - 0.5) * 0.06,
        vy: (Math.random() - 0.5) * 0.04,
        c: TINTS[i % TINTS.length],
      }))
    }

    const draw = (t: number) => {
      ctx.clearRect(0, 0, w, h)
      // planets: a soft radial wash, drawn first so stars sit on top
      for (const p of planets) {
        const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.r)
        g.addColorStop(0, `rgba(${p.c},${0.10 * dim})`)
        g.addColorStop(1, `rgba(${p.c},0)`)
        ctx.fillStyle = g
        ctx.beginPath()
        ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2)
        ctx.fill()
      }
      for (const s of stars) {
        const twinkle = reduce ? 0.8 : 0.55 + 0.45 * Math.sin(t * 0.0016 * s.z + s.x)
        ctx.fillStyle = `rgba(${s.c},${(0.15 + 0.55 * s.z) * twinkle * dim})`
        ctx.fillRect(s.x, s.y, s.r, s.r)
      }
    }

    let raf = 0
    let last = 0
    const frame = (t: number) => {
      const dt = Math.min(50, t - last) / 16.67
      last = t
      for (const s of stars) {
        s.x -= s.z * 0.14 * cfg.speed * dt
        if (s.x < -2) { s.x = w + 2; s.y = Math.random() * h }
      }
      for (const p of planets) {
        p.x += p.vx * cfg.speed * dt
        p.y += p.vy * cfg.speed * dt
        if (p.x < -p.r) p.x = w + p.r
        if (p.x > w + p.r) p.x = -p.r
        if (p.y < -p.r) p.y = h + p.r
        if (p.y > h + p.r) p.y = -p.r
      }
      draw(t)
      raf = requestAnimationFrame(frame)
    }

    seed()
    if (reduce) draw(0)
    else raf = requestAnimationFrame(frame)

    const onResize = () => { seed(); if (reduce) draw(0) }
    const onMotion = () => { cfg = read(); seed(); if (reduce) draw(0) }
    const onVisibility = () => {
      if (document.hidden) { cancelAnimationFrame(raf); raf = 0 }
      else if (!reduce && !raf) { last = performance.now(); raf = requestAnimationFrame(frame) }
    }
    window.addEventListener('resize', onResize)
    window.addEventListener('ce:motion', onMotion)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', onResize)
      window.removeEventListener('ce:motion', onMotion)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [dim])

  return <canvas ref={ref} className="ce-starfield" aria-hidden />
}

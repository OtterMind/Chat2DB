import { useEffect, useRef } from 'react'
import * as THREE from 'three'

/**
 * Live globe: the user's data connections as luminous nodes on a slowly rotating
 * 3D sphere; each new connection fires a light pulse travelling along an arc.
 * Three.js draws the globe; a live WebSocket tick (or a demo timer when no socket
 * is connected) triggers the pulses. Reduced-motion renders one static frame.
 */
const NODES = [
  { name: 'MySQL', color: 0x22c55e, pos: [0.3, 0.5, 0.8] },
  { name: 'Postgres', color: 0xfbbf24, pos: [-0.6, 0.3, 0.7] },
  { name: 'Mongo', color: 0x3b82f6, pos: [0.7, -0.2, 0.6] },
  { name: 'Redis', color: 0xef4444, pos: [-0.4, -0.5, 0.7] },
  { name: 'SQLite', color: 0xf97316, pos: [0.1, -0.7, 0.6] },
  { name: 'S3', color: 0xe5e7eb, pos: [-0.1, 0.7, 0.6] },
]

/**
 * The motion package, read live. `duration` scales how fast the globe turns and
 * its pulses travel (a shorter package duration means a quicker globe);
 * `particles` sizes the mote field around it; `ease` is the very same CSS timing
 * curve the rest of the app uses, solved here so a pulse eases along its arc
 * exactly like a card eases onto the screen.
 */
type MotionPkg = { particles?: number; duration?: number; ease?: string }

const KEYWORD_EASES: Record<string, [number, number, number, number]> = {
  linear: [0, 0, 1, 1],
  ease: [0.25, 0.1, 0.25, 1],
  'ease-in': [0.42, 0, 1, 1],
  'ease-out': [0, 0, 0.58, 1],
  'ease-in-out': [0.42, 0, 0.58, 1],
}

/** Solve a CSS `cubic-bezier(...)` into an easing function (Newton + bisection). */
function makeEase(css?: string): (t: number) => number {
  const spec = (css ?? '').trim()
  let c: [number, number, number, number] | undefined
  if (spec.startsWith('cubic-bezier(')) {
    const parts = spec.slice(13, -1).split(',').map((n) => Number(n.trim()))
    if (parts.length === 4 && parts.every((n) => Number.isFinite(n))) c = parts as [number, number, number, number]
  } else if (KEYWORD_EASES[spec]) c = KEYWORD_EASES[spec]
  if (!c) return (t) => t
  const [x1, y1, x2, y2] = c
  const bezier = (a: number, b: number, t: number) =>
    3 * a * (1 - t) ** 2 * t + 3 * b * (1 - t) * t ** 2 + t ** 3
  return (x: number) => {
    let t = x
    for (let i = 0; i < 5; i += 1) {
      const slope = 3 * x1 * (1 - t) ** 2 + 6 * (x2 - x1) * (1 - t) * t + 3 * (1 - x2) * t ** 2
      const err = bezier(x1, x2, t) - x
      if (Math.abs(slope) < 1e-6) break
      t -= err / slope
    }
    return bezier(y1, y2, Math.min(1, Math.max(0, t)))
  }
}

export default function LiveGlobe({ height = 300 }: { height?: number }) {
  const mount = useRef<HTMLDivElement>(null)
  const speed = useRef(1)
  const density = useRef(1)
  const ease = useRef<(t: number) => number>((t) => t)

  useEffect(() => {
    const read = () => {
      const m: MotionPkg = (window as any).__ceMotion ?? {}
      // energetic packages spin/pulse faster; calm slower. duration<1 = faster.
      speed.current = m.duration ? 1 / m.duration : 1
      // `particles` is expressed for a 100-point field; the globe draws 900 at
      // the built-in density, capped so a wild drop-in cannot hang the renderer.
      density.current = Math.min(4, Math.max(0, (m.particles ?? 8) / 8))
      ease.current = makeEase(m.ease)
    }
    read()
    window.addEventListener('ce:motion', read)
    return () => window.removeEventListener('ce:motion', read)
  }, [])

  useEffect(() => {
    const el = mount.current
    if (!el) return
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches

    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(45, el.clientWidth / height, 0.1, 100)
    camera.position.z = 3.2
    const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true })
    renderer.setSize(el.clientWidth, height)
    renderer.setPixelRatio(Math.min(2, window.devicePixelRatio))
    el.appendChild(renderer.domElement)

    const group = new THREE.Group()
    scene.add(group)

    // A generous field is allocated once; the active package decides how many
    // of its points are drawn, so switching packages never rebuilds geometry.
    const CAP = 3600
    const pts: number[] = []
    for (let i = 0; i < CAP; i++) {
      const v = new THREE.Vector3().randomDirection()
      pts.push(v.x, v.y, v.z)
    }
    const moteGeo = new THREE.BufferGeometry()
      .setAttribute('position', new THREE.Float32BufferAttribute(pts, 3))
    moteGeo.setDrawRange(0, Math.round(CAP * 0.25 * density.current))
    group.add(new THREE.Points(
      moteGeo,
      new THREE.PointsMaterial({ color: 0x00f0ff, size: 0.012, transparent: true, opacity: 0.5 }),
    ))

    const nodeMeshes = NODES.map((n) => {
      const m = new THREE.Mesh(
        new THREE.SphereGeometry(0.05, 16, 16),
        new THREE.MeshBasicMaterial({ color: n.color }),
      )
      m.position.set(n.pos[0], n.pos[1], n.pos[2])
      group.add(m)
      return m
    })

    const arcs: THREE.QuadraticBezierCurve3[] = []
    for (let i = 0; i < NODES.length; i++) {
      for (let j = i + 1; j < NODES.length; j += 2) {
        const a = nodeMeshes[i].position, b = nodeMeshes[j].position
        const mid = a.clone().add(b).multiplyScalar(0.5).normalize().multiplyScalar(1.5)
        const curve = new THREE.QuadraticBezierCurve3(a.clone(), mid, b.clone())
        arcs.push(curve)
        group.add(new THREE.Line(
          new THREE.BufferGeometry().setFromPoints(curve.getPoints(40)),
          new THREE.LineBasicMaterial({ color: 0x00f0ff, transparent: true, opacity: 0.25 }),
        ))
      }
    }

    const pulses = arcs.slice(0, 4).map((curve, i) => {
      const m = new THREE.Mesh(new THREE.SphereGeometry(0.03, 12, 12),
        new THREE.MeshBasicMaterial({ color: 0xff2d9c }))
      group.add(m)
      return { mesh: m, curve, t: i * 0.25, speed: 0.004 + i * 0.001 }
    })

    let raf = 0
    const tick = () => {
      if (!reduce) group.rotation.y += 0.0016 * speed.current
      moteGeo.setDrawRange(0, Math.round(CAP * 0.25 * density.current))
      for (const p of pulses) {
        p.t = (p.t + p.speed * speed.current) % 1
        // eased along the arc with the package's own curve
        p.mesh.position.copy(p.curve.getPoint(ease.current(p.t)))
      }
      renderer.render(scene, camera)
      if (!reduce) raf = requestAnimationFrame(tick)
    }
    tick()

    const onWs = () => pulses.forEach((p) => (p.speed = 0.012))
    window.addEventListener('ce:ws', onWs)
    const onResize = () => {
      renderer.setSize(el.clientWidth, height)
      camera.aspect = el.clientWidth / height
      camera.updateProjectionMatrix()
    }
    window.addEventListener('resize', onResize)

    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', onResize)
      window.removeEventListener('ce:ws', onWs)
      renderer.dispose()
      el.removeChild(renderer.domElement)
    }
  }, [height])

  return <div ref={mount} className="live-globe" style={{ height }} aria-hidden />
}

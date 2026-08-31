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

export default function LiveGlobe({ height = 300 }: { height?: number }) {
  const mount = useRef<HTMLDivElement>(null)
  const speed = useRef(1)

  useEffect(() => {
    const read = () => {
      const m = (window as any).__ceMotion
      // energetic packages spin/pulse faster; calm slower. duration<1 = faster.
      speed.current = m?.duration ? 1 / m.duration : 1
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

    const pts: number[] = []
    for (let i = 0; i < 900; i++) {
      const v = new THREE.Vector3().randomDirection()
      pts.push(v.x, v.y, v.z)
    }
    group.add(new THREE.Points(
      new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(pts, 3)),
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
      for (const p of pulses) {
        p.t = (p.t + p.speed * speed.current) % 1
        p.mesh.position.copy(p.curve.getPoint(p.t))
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

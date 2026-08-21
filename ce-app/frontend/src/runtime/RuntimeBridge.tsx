import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { wsClient } from '../api/websocket'
import { jobsApi } from '../api/jobs'
import { backendOrigin } from '../api/runtime'
import { useRuntime } from '../store/runtime'

const POLL_MS = 3000

/**
 * Mounted once, at the application root — never inside a route.
 *
 * It owns the single WebSocket connection and the polling loop, so switching
 * between tabs and features cannot interrupt a running pipeline: the sockets,
 * timers and progress state all live above the router.
 */
export function RuntimeBridge() {
  const queryClient = useQueryClient()

  useEffect(() => {
    const { upsertTask, patchTask, setWsConnected } = useRuntime.getState()

    wsClient.connect()
    setWsConnected(true)

    // Dev aid: lets us inspect/inject tasks from the console while designing.
    if (import.meta.env.DEV) {
      ;(window as unknown as Record<string, unknown>).__ceRuntime = useRuntime
    }

    const unsubscribe = wsClient.onEvent((event) => {
      switch (event.type) {
        case 'job:progress':
          upsertTask({
            id: event.job_id,
            kind: 'job',
            label: `پردازش ویدیو`,
            stage: event.stage,
            progress: event.progress,
            status: 'running',
            route: `/jobs/${event.job_id}`,
          })
          break
        case 'job:done':
          patchTask(event.job_id, { progress: 100, status: 'done', stage: 'آماده' })
          queryClient.invalidateQueries({ queryKey: ['jobs'] })
          queryClient.invalidateQueries({ queryKey: ['clips', event.job_id] })
          break
        case 'job:failed':
          patchTask(event.job_id, { status: 'failed', error: event.error })
          queryClient.invalidateQueries({ queryKey: ['jobs'] })
          break
        case 'job:clip_ready':
          queryClient.invalidateQueries({ queryKey: ['clips', event.job_id] })
          break
      }
    })

    // A dead backend used to look like an empty app: every screen degraded to
    // "no data" and only a POST ever surfaced an error. Now it is polled openly.
    const checkHealth = async () => {
      try {
        const response = await fetch(`${backendOrigin}/api/health`, { cache: 'no-store' })
        useRuntime.getState().setBackendOnline(response.ok)
      } catch {
        useRuntime.getState().setBackendOnline(false)
      }
    }
    void checkHealth()
    const health = window.setInterval(checkHealth, 5000)

    // Safety net: if the socket drops, polling still keeps the dock accurate.
    const timer = window.setInterval(async () => {
      const state = useRuntime.getState()
      const hasRunning = Object.values(state.tasks).some((t) => t.status === 'running' && t.kind === 'job')
      if (!hasRunning) return
      try {
        const { jobs } = await jobsApi.list(1, 20)
        for (const job of jobs) {
          if (job.status === 'processing') {
            state.upsertTask({
              id: job.id,
              kind: 'job',
              label: job.name || 'پردازش ویدیو',
              stage: job.current_stage ?? undefined,
              progress: job.progress,
              status: 'running',
              route: `/jobs/${job.id}`,
            })
          } else if (state.tasks[job.id]) {
            state.patchTask(job.id, {
              progress: job.status === 'done' ? 100 : state.tasks[job.id].progress,
              status: job.status === 'done' ? 'done' : job.status === 'failed' ? 'failed' : 'running',
              error: job.error ?? undefined,
            })
          }
        }
      } catch {
        /* backend not reachable yet — keep the last known state */
      }
    }, POLL_MS)

    return () => {
      // Only on full app teardown; route changes never reach this.
      unsubscribe()
      window.clearInterval(timer)
      window.clearInterval(health)
    }
  }, [queryClient])

  return null
}

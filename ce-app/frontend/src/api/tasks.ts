import api from './client'
import { wsClient } from './websocket'

/**
 * Watching long work.
 *
 * The backend runs style analysis and styled rebuilds as tasks: the POST returns
 * in milliseconds and the progress arrives over the WebSocket. Polling is the
 * fallback, not the plan — but it is a real fallback, because a socket that
 * dropped is exactly when a progress bar must not freeze at 40 %.
 */

export interface TaskState {
  id: string
  kind: string
  status: 'running' | 'done' | 'failed' | 'cancelled'
  stage: string
  progress: number
  label: string
  elapsed: number
  error: string | null
  result?: unknown
}

/** Long enough for the poll itself; the *work* is not on this budget any more. */
const POLL_TIMEOUT = { timeout: 20_000 }

export const tasksApi = {
  get: async (id: string): Promise<TaskState> =>
    (await api.get(`/tasks/${id}`, POLL_TIMEOUT)).data,
  cancel: async (id: string): Promise<TaskState> =>
    (await api.post(`/tasks/${id}/cancel`, {}, POLL_TIMEOUT)).data,
  list: async (): Promise<{ tasks: TaskState[] }> => (await api.get('/tasks', POLL_TIMEOUT)).data,
}

/**
 * Follow a task to its end.
 *
 * Resolves with the finished state (result included) or rejects with the error
 * the worker reported. `onProgress` fires for every stage change, from the socket
 * when it is up and from the poll when it is not.
 */
export function followTask(
  id: string,
  onProgress: (state: TaskState) => void,
  options: { pollMs?: number } = {}
): { promise: Promise<TaskState>; cancel: () => void } {
  const pollMs = options.pollMs ?? 1000
  let settled = false
  let stop: (() => void) | undefined
  let timer: number | undefined

  const promise = new Promise<TaskState>((resolve, reject) => {
    const finish = async (state: TaskState) => {
      if (settled) return
      settled = true
      stop?.()
      if (timer) window.clearInterval(timer)
      if (state.status === 'done') {
        // The result never travels over the socket — it is fetched here.
        try {
          resolve(await tasksApi.get(id))
        } catch (error) {
          reject(error)
        }
      } else if (state.status === 'cancelled') {
        reject(Object.assign(new Error('cancelled'), { cancelled: true }))
      } else {
        reject(new Error(state.error || 'The task failed'))
      }
    }

    stop = wsClient.onEvent((event) => {
      const message = event as unknown as { type?: string; task_id?: string } & TaskState
      if (!message.type?.startsWith('task:') || message.task_id !== id) return
      onProgress(message)
      if (message.status !== 'running') void finish(message)
    })

    timer = window.setInterval(async () => {
      if (settled) return
      try {
        const state = await tasksApi.get(id)
        onProgress(state)
        if (state.status !== 'running') void finish(state)
      } catch {
        /* one missed poll is not a failure; the next one decides */
      }
    }, pollMs)
  })

  return {
    promise,
    cancel: () => {
      void tasksApi.cancel(id).catch(() => undefined)
    },
  }
}

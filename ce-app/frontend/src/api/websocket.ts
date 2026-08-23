import { backendWebSocketUrl } from './runtime'

export type JobEvent =
  | { type: 'job:progress'; job_id: string; stage: string; progress: number; message?: string }
  | { type: 'job:done'; job_id: string; clips_count: number }
  | { type: 'job:failed'; job_id: string; error: string }
  | { type: 'job:clip_ready'; job_id: string; clip_id: string; preview_url: string }
  // Long work started by a screen (style analysis, styled rebuild): the same
  // channel, because there is only ever one socket and every screen has it.
  | {
      type: 'task:progress' | 'task:done' | 'task:failed' | 'task:cancelled'
      task_id: string
      kind: string
      status: 'running' | 'done' | 'failed' | 'cancelled'
      stage: string
      progress: number
      label: string
      elapsed: number
      error: string | null
    }

class WebSocketClient {
  private socket: WebSocket | null = null
  private listeners: Array<(event: JobEvent) => void> = []
  private reconnectAttempts = 0

  connect() {
    if (this.socket?.readyState === WebSocket.OPEN) return
    this.socket = new WebSocket(backendWebSocketUrl('/ws'))
    this.socket.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data) as JobEvent
        this.listeners.forEach((fn) => fn(data))
      } catch (e) { console.error('WS parse error', e) }
    }
    this.socket.onclose = () => {
      if (this.reconnectAttempts < 5) {
        setTimeout(() => { this.reconnectAttempts++; this.connect() }, 1000 * this.reconnectAttempts)
      }
    }
    this.socket.onopen = () => { this.reconnectAttempts = 0 }
  }
  onEvent(fn: (event: JobEvent) => void) {
    this.listeners.push(fn)
    return () => { this.listeners = this.listeners.filter((l) => l !== fn) }
  }
  disconnect() { this.socket?.close(); this.socket = null }
}

export const wsClient = new WebSocketClient()
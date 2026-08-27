import axios from 'axios'
import { backendOrigin } from './runtime'

const api = axios.create({
  baseURL: `${backendOrigin}/api`,
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
})

export default api

/* A11 (advisors): one place for error UX + timing.
   503 with a detail → a readable warning (e.g. "model not installed");
   404 on optional status endpoints stays silent; every request carries a
   correlation id and logs its ms so slow endpoints are visible. */
import { message } from 'antd'

const OPTIONAL_404 = ['/ai/cuda/status', '/engines/status', '/captions/align-status']

api.interceptors.request.use((config) => {
  config.headers = config.headers ?? {}
  ;(config.headers as Record<string, string>)['x-ce-cid'] =
    Math.random().toString(36).slice(2, 10)
  ;(config as unknown as { _ceT0: number })._ceT0 = performance.now()
  return config
})

api.interceptors.response.use(
  (res) => {
    const t0 = (res.config as unknown as { _ceT0?: number })._ceT0
    if (t0) console.debug(`[ce] ${res.config.url} ${Math.round(performance.now() - t0)}ms`)
    return res
  },
  (err) => {
    const status = err?.response?.status
    const url: string = err?.config?.url ?? ''
    if (status === 404 && OPTIONAL_404.some((p) => url.includes(p))) return Promise.reject(err)
    if (status === 503) {
      const detail = err?.response?.data?.detail
      if (detail) message.warning(String(detail))
    }
    return Promise.reject(err)
  }
)

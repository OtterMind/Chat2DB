import api from './client'
import { followTask, type TaskState } from './tasks'

export interface OcrStatus {
  installed: boolean
  licence: string
  runtimeDir: string
  modelsBundled: boolean
}

export const ocrApi = {
  status: async (): Promise<OcrStatus> => (await api.get('/ocr/status')).data,
  /** Fetch RapidOCR and its small deps once, into the user's runtime dir. */
  install: async (onProgress: (state: TaskState) => void): Promise<unknown> => {
    const started = (await api.post('/ocr/install/start')).data as TaskState
    const follow = followTask(started.id, onProgress)
    return (await follow.promise).result
  },
  /** How much of a video carries on-screen text, 0..1. */
  coverage: async (path: string, every = 3): Promise<{ coverage: number }> =>
    (await api.post('/ocr/coverage', { path, every }, { timeout: 300_000 })).data,
}

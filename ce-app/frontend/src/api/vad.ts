import api from './client'
import { followTask, type TaskState } from './tasks'

export interface VadStatus {
  model: boolean
  modelPath: string
  modelMb: number
  onnxruntime: string | null
  ready: boolean
  licence: string
  /** Which speech map the edit is built on: 'energy' | 'silero'. */
  engine: string
  choices: string[]
}

export interface VadMethod {
  speechRatio: number
  regions: number
  seconds: number
  first: number | null
}

export interface VadComparison {
  file: string
  duration: number
  hasAudio: boolean
  ready: boolean
  silencedetect: VadMethod | null
  silero: VadMethod | null
  /** Seconds where exactly one of the two says "speech", as a share of the file. */
  disagreementRatio?: number
}

export const vadApi = {
  status: async (): Promise<VadStatus> => (await api.get('/vad/status')).data,
  choose: async (engine: string): Promise<{ engine: string }> =>
    (await api.post('/vad/choose', { engine })).data,
  /** 2.2 MB, as a task with a real progress bar. */
  install: async (onProgress: (state: TaskState) => void): Promise<VadStatus> => {
    const started = (await api.post('/vad/install/start')).data as TaskState
    const follow = followTask(started.id, onProgress)
    return (await follow.promise).result as VadStatus
  },
  compare: async (path: string): Promise<VadComparison> =>
    (await api.post('/vad/compare', { path }, { timeout: 300_000 })).data,
}

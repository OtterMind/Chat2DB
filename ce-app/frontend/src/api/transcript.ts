import api from './client'

export interface Range { start: number; end: number }

export interface JumpcutResult {
  cuts: Range[]
  keep: Range[]
  duration: number
  removed: number
  kept: number
}

export const transcriptApi = {
  cuts: async (words: Record<string, unknown>[], spans: number[][]) =>
    (await api.post('/transcript/cuts', { words, spans })).data as { cuts: Range[]; removed: number },
  fillers: async (words: Record<string, unknown>[], lang?: string) =>
    (await api.post('/transcript/fillers', { words, lang })).data as { cuts: Range[]; count: number; removed: number },
  jumpcut: async (q: {
    words?: Record<string, unknown>[]
    silences?: Range[]
    remove_fillers?: boolean
    remove_silence?: boolean
    minimum_silence?: number
  }) => (await api.post('/transcript/jumpcut', q)).data as JumpcutResult,
}

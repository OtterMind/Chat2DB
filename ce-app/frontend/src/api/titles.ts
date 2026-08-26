import api from './client'
import type { Keyframe } from '../editor/model'

export interface TitlePreset {
  id: string
  en: string
  fa: string
  /** entrance · hold · caption */
  category: string
  duration: number
  keyframes: Keyframe[]
  props: {
    position?: 'top' | 'middle' | 'bottom'
    textStyle?: 'clean' | 'boxed' | 'outline' | 'shadow'
    animateWords?: boolean
  }
}

export interface TitlePack {
  /**
   * The five channels the exporter can genuinely animate. The pack is built from
   * nothing else, and the backend refuses a preset that reaches for another —
   * a title that animates in the monitor and sits still in the export is the bug
   * this whole feature exists to avoid.
   */
  channels: string[]
  presets: TitlePreset[]
}

export const titlesApi = {
  pack: async (): Promise<TitlePack> => (await api.get('/titles')).data,
}

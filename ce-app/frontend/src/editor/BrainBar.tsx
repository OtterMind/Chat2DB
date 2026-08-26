import { useEffect, useState } from 'react'
import { useEditor } from './model'
import { useRuntime, selectActiveTasks } from '../store/runtime'
import { useI18n } from '../i18n'
import { backendOrigin } from '../api/runtime'

declare const __APP_VERSION__: string

/**
 * The living status strip (items 9+13): autosave-proof clip count, canvas
 * shape, encode device, app version — and a brain chip that visibly *pulses*
 * while any task (analyse, render, align) is thinking. The AI is not a hidden
 * box; its heartbeat sits where the eye rests.
 */
export default function BrainBar() {
  const { t } = useI18n()
  const clips = useEditor((s) => s.clips)
  const aspect = useEditor((s) => s.aspect)
  const thinking = useRuntime(selectActiveTasks).length > 0
  const [gpu, setGpu] = useState('CPU')

  useEffect(() => {
    fetch(`${backendOrigin}/api/gpu/status`)
      .then((r) => r.json())
      .then((d) => {
        const enc = d?.encode ?? []
        setGpu(Array.isArray(enc) && enc.length ? 'GPU' : 'CPU')
      })
      .catch(() => setGpu('CPU'))
  }, [])

  return (
    <div className="brainbar" dir="ltr">
      <span className="pulse" style={thinking ? undefined : { animationPlayState: 'paused', opacity: .5 }} />
      <span>{thinking ? t('brain is thinking…', 'مغز در حال فکرکردن…') : t('brain ready', 'مغز آماده')}</span>
      <span className="sp" />
      <span className="mono">{clips.length} {t('clips', 'کلیپ')}</span>
      <span className="mono">{aspect}</span>
      <span className="mono">{gpu}</span>
      <span className="mono">v{__APP_VERSION__}</span>
    </div>
  )
}

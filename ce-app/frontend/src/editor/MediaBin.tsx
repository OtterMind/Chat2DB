import { useMemo } from 'react'
import { FolderOpen, Film, AudioLines, Type } from 'lucide-react'
import { useEditor, formatTimecode } from './model'
import { useI18n } from '../i18n'
import { backendOrigin } from '../api/runtime'

/**
 * The library pane: what the project already holds, one row per source file.
 * A real NLE's media bin, minus the weight: rows are derived from the timeline
 * itself, so the bin can never disagree with the edit.
 */
export default function MediaBin({ onImport }: { onImport: () => void }) {
  const { t } = useI18n()
  const { clips, select } = useEditor()

  const rows = useMemo(() => {
    const bySrc = new Map<string, { src: string; label: string; color: string; dur: number; id: string; kind: string }>()
    for (const c of clips) {
      if (!c.src) continue
      const row = bySrc.get(c.src)
      if (row) row.dur += c.duration
      else bySrc.set(c.src, { src: c.src, label: c.label, color: c.color, dur: c.duration, id: c.id, kind: c.src.match(/\.(mp3|m4a|wav|aac|ogg)$/i) ? 'audio' : 'video' })
    }
    return [...bySrc.values()]
  }, [clips])

  return (
    <aside className="ed__bin" aria-label={t('Library', 'کتابخانه')}>
      <div className="ed__bin-head">
        <strong>{t('Library', 'کتابخانه')}</strong>
        <button className="ed__btn ed__btn--sm" onClick={onImport} title={t('Import media', 'افزودن رسانه')}>
          <FolderOpen size={13} /> {t('Add', 'افزودن')}
        </button>
      </div>
      {rows.length === 0 && (
        <p className="ce-hint" style={{ padding: '10px 12px' }}>
          {t('Nothing here yet — add a file and it appears in the bin.', 'هنوز خالی است — فایلی اضافه کن تا اینجا دیده شود.')}
        </p>
      )}
      {rows.map((r) => (
        <button key={r.src} className="ed__binrow" onClick={() => select(r.id)} title={r.src}>
          {r.kind === 'video' ? (
            <img
              className="ed__binthumb"
              src={`${backendOrigin}/api/media/thumb?path=${encodeURIComponent(r.src)}&t=0.5&h=56`}
              alt=""
              loading="lazy"
            />
          ) : (
            <span className="ed__binthumb ed__binthumb--audio">
              {r.kind === 'audio' ? <AudioLines size={16} /> : <Film size={16} />}
            </span>
          )}
          <span className="ed__binmeta">
            <strong>{r.label}</strong>
            <span className="mono" dir="ltr">{formatTimecode(r.dur)}</span>
          </span>
          <span className="ed__bindot" style={{ background: r.color }} />
        </button>
      ))}
      <p className="ce-hint" style={{ padding: '8px 12px' }}>
        <Type size={11} /> {t('Text clips live on the timeline, not in the bin.', 'کلیپ‌های متن روی تایم‌لاین‌اند، نه در بین.')}
      </p>
    </aside>
  )
}

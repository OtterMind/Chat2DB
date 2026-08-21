import { useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  Play, Pause, Scissors, Copy, Trash2, Undo2, Redo2, Magnet, ZoomIn, ZoomOut,
  Plus, SkipBack, Info,
} from 'lucide-react'
import Page from '../components/Page'
import Timeline from '../editor/Timeline'
import { formatTimecode, useEditor, TIMELINE_MAX } from '../editor/model'

const TOOL_NOTE: Record<string, string> = {
  bgremove: 'حذف پس‌زمینه بعد از اتصال موتور رندر فعال می‌شود.',
  enhance: 'ارتقای کیفیت بعد از اتصال موتور رندر فعال می‌شود.',
  titles: 'قالب‌های تیتراژ روی لایه‌ی متن اضافه خواهند شد.',
  music: 'میکس و داکینگ صدا روی لایه‌ی صدا اعمال می‌شود.',
}

export default function Studio() {
  const [params] = useSearchParams()
  const note = params.get('tool') ? TOOL_NOTE[params.get('tool')!] : undefined

  const {
    playing, playhead, pxPerSecond, snapping, selectedId, clips, past, future,
    togglePlay, setPlayhead, setZoom, toggleSnapping, splitAtPlayhead,
    removeSelected, duplicateSelected, undo, redo, addTrack,
  } = useEditor()

  // playback clock — advances the playhead until the last clip ends
  const raf = useRef<number>()
  useEffect(() => {
    if (!playing) return
    let last = performance.now()
    const end = Math.max(0, ...clips.map((c) => c.start + c.duration))
    const tick = (now: number) => {
      const dt = (now - last) / 1000
      last = now
      const next = useEditor.getState().playhead + dt
      if (next >= Math.min(end, TIMELINE_MAX)) {
        setPlayhead(end)
        togglePlay(false)
        return
      }
      setPlayhead(next)
      raf.current = requestAnimationFrame(tick)
    }
    raf.current = requestAnimationFrame(tick)
    return () => {
      if (raf.current) cancelAnimationFrame(raf.current)
    }
  }, [playing, clips, setPlayhead, togglePlay])

  // editor keyboard shortcuts
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) return
      const meta = e.ctrlKey || e.metaKey
      if (meta && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo(); return }
      if (meta && e.key.toLowerCase() === 'd') { e.preventDefault(); duplicateSelected(); return }
      switch (e.key) {
        case ' ': e.preventDefault(); togglePlay(); break
        case 's': case 'S': splitAtPlayhead(); break
        case 'Delete': case 'Backspace': removeSelected(); break
        case 'Home': setPlayhead(0); break
        case 'ArrowRight': setPlayhead(playhead + (e.shiftKey ? 1 : 1 / 30)); break
        case 'ArrowLeft': setPlayhead(playhead - (e.shiftKey ? 1 : 1 / 30)); break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [duplicateSelected, playhead, redo, removeSelected, setPlayhead, splitAtPlayhead, togglePlay, undo])

  return (
    <Page title="میز تدوین" subtitle="تایم‌لاین چندلایه — برش، جابه‌جایی و تریم" width="lg">
      <div className="ed">
        <div className="ed__stage">
          <div className="ed__preview">
            <div className="ed__preview-box">
              <span className="ed__preview-hint">پیش‌نمایش</span>
              <strong className="ed__tc">{formatTimecode(playhead, true)}</strong>
            </div>
          </div>

          <aside className="ed__inspector">
            <h4>مشخصات</h4>
            {selectedId ? (
              (() => {
                const clip = clips.find((c) => c.id === selectedId)!
                return (
                  <>
                    <div className="ce-kv"><span>نام</span><strong>{clip.label}</strong></div>
                    <div className="ce-kv"><span>شروع</span><strong className="ce-num" dir="ltr">{formatTimecode(clip.start, true)}</strong></div>
                    <div className="ce-kv"><span>مدت</span><strong className="ce-num" dir="ltr">{formatTimecode(clip.duration, true)}</strong></div>
                    <div className="ce-kv"><span>نقطه ورود</span><strong className="ce-num" dir="ltr">{formatTimecode(clip.offset, true)}</strong></div>
                  </>
                )
              })()
            ) : (
              <p className="ce-hint">یک کلیپ را انتخاب کن تا مشخصاتش اینجا بیاید.</p>
            )}
          </aside>
        </div>

        <div className="ed__toolbar">
          <div className="ed__group">
            <button className="ed__btn" onClick={() => setPlayhead(0)} title="برگشت به ابتدا"><SkipBack size={16} /></button>
            <button className="ed__btn ed__btn--primary" onClick={() => togglePlay()} title="پخش / مکث (Space)">
              {playing ? <Pause size={16} /> : <Play size={16} />}
            </button>
            <span className="ed__tc ed__tc--sm" dir="ltr">{formatTimecode(playhead, true)}</span>
          </div>

          <div className="ed__group">
            <button className="ed__btn" onClick={splitAtPlayhead} title="برش در محل پلی‌هد (S)"><Scissors size={16} /></button>
            <button className="ed__btn" onClick={duplicateSelected} disabled={!selectedId} title="تکثیر (Ctrl+D)"><Copy size={16} /></button>
            <button className="ed__btn" onClick={removeSelected} disabled={!selectedId} title="حذف (Delete)"><Trash2 size={16} /></button>
          </div>

          <div className="ed__group">
            <button className="ed__btn" onClick={undo} disabled={past.length === 0} title="واگرد (Ctrl+Z)"><Undo2 size={16} /></button>
            <button className="ed__btn" onClick={redo} disabled={future.length === 0} title="ازنو (Ctrl+Shift+Z)"><Redo2 size={16} /></button>
            <button className={`ed__btn ${snapping ? 'is-on' : ''}`} onClick={toggleSnapping} title="چسبندگی"><Magnet size={16} /></button>
          </div>

          <div className="ed__group">
            <button className="ed__btn" onClick={() => setZoom(pxPerSecond / 1.35)} title="کوچک‌نمایی"><ZoomOut size={16} /></button>
            <input
              className="ed__zoom" type="range" min={8} max={220} value={pxPerSecond}
              onChange={(e) => setZoom(Number(e.target.value))} aria-label="بزرگ‌نمایی"
            />
            <button className="ed__btn" onClick={() => setZoom(pxPerSecond * 1.35)} title="بزرگ‌نمایی"><ZoomIn size={16} /></button>
          </div>

          <div className="ed__group ed__group--end">
            <button className="ed__btn" onClick={() => addTrack('video')} title="لایه ویدیو"><Plus size={14} /> ویدیو</button>
            <button className="ed__btn" onClick={() => addTrack('audio')} title="لایه صدا"><Plus size={14} /> صدا</button>
            <button className="ed__btn" onClick={() => addTrack('text')} title="لایه متن"><Plus size={14} /> متن</button>
          </div>
        </div>

        <Timeline />

        <div className="ce-note">
          <Info size={16} />
          <span>
            {note ??
              'این تایم‌لاین کاملاً کار می‌کند: کلیپ‌ها را بکش، لبه‌ها را تریم کن، با کلید S برش بزن و با Ctrl+Z برگرد. اتصال به موتور رندر و پیش‌نمایش ویدیوی واقعی، قدم بعدی است.'}
          </span>
        </div>
      </div>
    </Page>
  )
}

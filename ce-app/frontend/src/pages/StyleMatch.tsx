import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { message, Modal, Input } from 'antd'
import {
  Sparkles, FileVideo, Wand2, Trash2, Loader2, Film, Music4, Gauge, Crop as CropIcon, Info, XCircle,
} from 'lucide-react'
import Page, { Card } from '../components/Page'
import { styleApi, type StyleTemplate, type TemplateSummary, type StyledEdit } from '../api/style'
import type { TaskState } from '../api/tasks'
import { pickMedia } from '../api/render'
import { useEditor, formatTimecode } from '../editor/model'
import { useI18n } from '../i18n'

/**
 * Style Match.
 *
 * Two files and one idea: measure a video you like, then rebuild *your* footage
 * with the same editing grammar — shot rhythm, camera moves, colour, aspect,
 * transitions. Nothing of the reference is copied; the template is numbers.
 */
export default function StyleMatch() {
  const { t } = useI18n()
  const navigate = useNavigate()
  const [templates, setTemplates] = useState<TemplateSummary[]>([])
  const [template, setTemplate] = useState<StyleTemplate | null>(null)
  const [busy, setBusy] = useState<'analyse' | 'apply' | null>(null)
  const [result, setResult] = useState<StyledEdit | null>(null)
  /** What the work is doing right now — the screen used to be able to say only "busy". */
  const [progress, setProgress] = useState<TaskState | null>(null)
  const [elapsed, setElapsed] = useState(0)
  const stopRef = useRef<(() => void) | null>(null)

  /** A second counter of our own: the socket reports stages, not ticks. */
  useEffect(() => {
    if (!busy) return undefined
    const began = Date.now()
    setElapsed(0)
    const timer = window.setInterval(() => setElapsed(Math.round((Date.now() - began) / 1000)), 500)
    return () => window.clearInterval(timer)
  }, [busy])

  /** Everything a long call needs to stay honest on screen. */
  const watcher = {
    onProgress: (state: TaskState) => setProgress(state),
    onStart: (cancel: () => void) => { stopRef.current = cancel },
  }

  const clearWork = () => {
    stopRef.current = null
    setProgress(null)
    setBusy(null)
  }

  const wasCancelled = (error: unknown) => Boolean((error as { cancelled?: boolean })?.cancelled)

  const refresh = () => styleApi.templates().then((r) => setTemplates(r.templates)).catch(() => undefined)
  useEffect(() => {
    refresh()
  }, [])

  const choose = async (): Promise<string | null> => {
    const picker = pickMedia()
    if (picker) {
      const paths = await picker
      return paths[0] ?? null
    }
    return new Promise((resolve) => {
      let value = ''
      Modal.confirm({
        title: t('Path to the video', 'مسیر فایل ویدیو'),
        icon: null,
        content: <Input autoFocus placeholder="/path/to/video.mp4" onChange={(e) => (value = e.target.value)} />,
        okText: t('Use this file', 'همین فایل'),
        cancelText: t('Cancel', 'انصراف'),
        onOk: () => resolve(value.trim() || null),
        onCancel: () => resolve(null),
      })
    })
  }

  const analyse = async () => {
    const path = await choose()
    if (!path) return
    setBusy('analyse')
    try {
      const found = await styleApi.analyse(path, undefined, watcher)
      setTemplate(found)
      refresh()
      message.success(
        t(`Template ready — ${found.shots.length} shots`, `قالب آماده شد — ${found.shots.length} نما`)
      )
    } catch (err) {
      if (wasCancelled(err)) message.info(t('Stopped', 'متوقف شد'))
      else message.error((err as Error).message)
    } finally {
      clearWork()
    }
  }

  /**
   * The automatic door.
   *
   * The editor works by prompt; this screen works by itself. One button: the
   * reference, your footage, an optional music bed — then it measures, cuts,
   * grades, animates, captions where it can, ducks the music and opens the
   * result. No parameters to choose, and whatever it could not do is listed.
   */
  const runEverything = async () => {
    const referencePath = await choose()
    if (!referencePath) return
    setBusy('analyse')
    try {
      const found = await styleApi.analyse(referencePath, undefined, watcher)
      setTemplate(found)
      refresh()

      const ownPath = await choose()
      if (!ownPath) return
      const musicPath = await askForMusic()

      setBusy('apply')
      const built = await styleApi.apply(
        ownPath,
        found.name,
        t('Styled edit', 'تدوین بر اساس الگو'),
        musicPath,
        watcher
      )
      setResult(built)

      const editor = useEditor.getState()
      editor.loadSnapshot(built.timeline as never, built.name)
      editor.setAspect((built.aspect as never) ?? 'auto')
      message.success(
        t(`Ready — ${built.summary.shots} shots`, `آماده شد — ${built.summary.shots} نما`)
      )
      navigate('/studio')
    } catch (err) {
      if (wasCancelled(err)) message.info(t('Stopped', 'متوقف شد'))
      else message.error((err as Error).message)
    } finally {
      clearWork()
    }
  }

  /** Optional: a music bed of the user's own, ducked under the voice. */
  const askForMusic = (): Promise<string | null> =>
    new Promise((resolve) => {
      Modal.confirm({
        title: t('Add a music bed?', 'موسیقی هم اضافه شود؟'),
        icon: null,
        content: (
          <p className="ce-hint">
            {t(
              'Optional. Your own track — the template only carries the tempo and how far the music sits under the voice.',
              'اختیاری است. آهنگ خودت — قالب فقط تمپو و میزان پایین رفتن موسیقی زیر صدا را نگه می‌دارد.'
            )}
          </p>
        ),
        okText: t('Choose a track', 'انتخاب آهنگ'),
        cancelText: t('No music', 'بدون موسیقی'),
        onOk: async () => resolve(await choose()),
        onCancel: () => resolve(null),
      })
    })

  const applyTo = async () => {
    if (!template) return
    const path = await choose()
    if (!path) return
    setBusy('apply')
    try {
      const built = await styleApi.apply(
        path, template.name, t('Styled edit', 'تدوین بر اساس الگو'), null, watcher
      )
      setResult(built)
      message.success(t('Your edit is ready', 'تدوین تو آماده است'))
    } catch (err) {
      if (wasCancelled(err)) message.info(t('Stopped', 'متوقف شد'))
      else message.error((err as Error).message)
    } finally {
      clearWork()
    }
  }

  const openInEditor = () => {
    if (!result) return
    const editor = useEditor.getState()
    editor.loadSnapshot(result.timeline as never, result.name)
    editor.setAspect((result.aspect as never) ?? 'auto')
    navigate('/studio')
  }

  const percent = (value: number) => `${Math.round(value * 100)}%`

  return (
    <Page
      title={t('Style Match', 'ساخت شبیه الگو')}
      subtitle={t(
        'Measure a video you like, then cut your own footage the same way',
        'یک ویدیوی الگو را اندازه بگیر، بعد فیلم خودت را همان‌طور تدوین کن'
      )}
      width="md"
      back
    >
      <Card title={t('1 · The reference', '۱ · ویدیوی الگو')}>
        <p className="ce-hint">
          {t(
            'Nothing of the reference is copied — the template holds numbers: shot lengths, tempo, camera moves, colour, aspect.',
            'هیچ‌چیزی از ویدیوی الگو کپی نمی‌شود — قالب فقط عدد نگه می‌دارد: طول نماها، تمپو، حرکت دوربین، رنگ و نسبت تصویر.'
          )}
        </p>
        <div className="ce-actions" style={{ marginTop: 12 }}>
          <button className="ce-btn ce-btn--sm ce-btn--auto" disabled={busy !== null} onClick={() => void runEverything()}>
            {busy ? <Loader2 size={15} className="ce-spin" /> : <Wand2 size={15} />}
            {t('Do everything automatically', 'همه‌کار را خودکار انجام بده')}
          </button>
          <button className="ce-btn ce-btn--ghost ce-btn--sm" disabled={busy !== null} onClick={() => void analyse()}>
            <FileVideo size={15} /> {t('Only analyse a reference', 'فقط الگو را تحلیل کن')}
          </button>
        </div>
        {busy && (
          <div className="ce-work" data-testid="style-progress" data-stage={progress?.stage ?? 'starting'}>
            <div className="ce-work__head">
              <Loader2 size={15} className="ce-spin" />
              <strong data-testid="style-progress-label">
                {progress?.label || t('Starting…', 'در حال شروع…')}
              </strong>
              <span className="ce-work__time" dir="ltr" data-testid="style-progress-elapsed">
                {elapsed}s
              </span>
              <button
                className="ce-btn ce-btn--ghost ce-btn--sm"
                data-testid="style-cancel"
                onClick={() => stopRef.current?.()}
              >
                <XCircle size={14} /> {t('Stop', 'توقف')}
              </button>
            </div>
            <div className="ce-work__track">
              <div
                className="ce-work__fill"
                data-testid="style-progress-fill"
                style={{ width: `${Math.round((progress?.progress ?? 0) * 100)}%` }}
              />
            </div>
            <p className="ce-hint" style={{ marginTop: 6 }}>
              {t(
                'It keeps working if you look away — a long reference takes a while, and nothing here is waiting on a 30-second budget any more.',
                'اگر صفحه را رها کنی هم ادامه می‌دهد — یک الگوی طولانی زمان می‌برد، و دیگر هیچ‌چیز به بودجهٔ سی‌ثانیه‌ای بسته نیست.'
              )}
            </p>
          </div>
        )}

        <p className="ce-hint" style={{ marginTop: 8 }}>
          {t(
            'Automatic means: no prompt and no settings — reference in, your footage in, finished timeline out.',
            'خودکار یعنی: نه پرامپتی، نه تنظیماتی — الگو بده، فیلم خودت را بده، تایم‌لاین آماده تحویل بگیر.'
          )}
        </p>

        {templates.length > 0 && (
          <div className="ce-reel" style={{ marginTop: 14 }}>
            {templates.map((item) => (
              <div
                key={item.name}
                className={`ce-reelcard ${template?.name === item.name ? 'is-unfinished' : ''}`}
                role="button"
                tabIndex={0}
                onClick={() => void styleApi.templates().then(async () => {
                  const full = await fetch(
                    `${location.origin.includes('5173') ? 'http://127.0.0.1:8742' : ''}/api/style/templates/${encodeURIComponent(item.name)}`
                  ).then((r) => r.json())
                  setTemplate(full)
                })}
                onKeyDown={() => undefined}
              >
                <span className="ce-reelcard__art">
                  <Sparkles size={18} />
                  <span className="ce-reelcard__len" dir="ltr">{formatTimecode(item.duration)}</span>
                  <button
                    className="ce-reelcard__del"
                    title={t('Delete', 'حذف')}
                    onClick={(event) => {
                      event.stopPropagation()
                      void styleApi.remove(item.name).then(refresh)
                    }}
                  >
                    <Trash2 size={13} />
                  </button>
                </span>
                <span className="ce-reelcard__name">{item.name}</span>
                <span className="ce-reelcard__meta" dir="ltr">
                  {item.shots} shots · {Math.round(item.bpm)} BPM
                </span>
              </div>
            ))}
          </div>
        )}
      </Card>

      {template && (
        <Card title={t('2 · What the template says', '۲ · قالب چه می‌گوید')}>
          <div className="ce-badges">
            <span className="ce-badge"><Film size={13} /> {template.shots.length} {t('shots', 'نما')}</span>
            <span className="ce-badge"><Gauge size={13} /> {t('median', 'میانه')} {template.median_shot.toFixed(2)}s</span>
            <span className="ce-badge"><Music4 size={13} /> {Math.round(template.bpm)} BPM</span>
            <span className="ce-badge"><CropIcon size={13} /> {template.aspect}</span>
            <span className="ce-badge">{t('cuts on beat', 'برش روی ضرب')} {percent(template.cuts_on_beat)}</span>
            <span className="ce-badge">{t('speech', 'گفتار')} {percent(template.speech_ratio)}</span>
          </div>

          <div className="ce-kv" style={{ marginTop: 10 }}>
            <span>{t('Camera', 'دوربین')}</span>
            <strong dir="ltr">
              {Object.entries(template.motion_mix)
                .filter(([, share]) => share > 0)
                .map(([kind, share]) => `${kind} ${percent(share)}`)
                .join(' · ')}
            </strong>
          </div>
          <div className="ce-kv">
            <span>{t('Transitions', 'ترنزیشن')}</span>
            <strong dir="ltr">{String(template.transitions.type)} × {String(template.transitions.count)}</strong>
          </div>

          <p className="ce-hint" style={{ marginTop: 10 }}>
            <Info size={14} /> {t('Not measurable from pixels:', 'از روی تصویر قابل اندازه‌گیری نیست:')}{' '}
            {template.unknown.join(' · ')}
          </p>

          <div className="ce-actions" style={{ marginTop: 14 }}>
            <button className="ce-btn ce-btn--sm" disabled={busy !== null} onClick={() => void applyTo()}>
              {busy === 'apply' ? <Loader2 size={15} className="ce-spin" /> : <Wand2 size={15} />}
              {t('Use my footage', 'روی فیلم خودم اعمال کن')}
            </button>
          </div>
        </Card>
      )}

      {result && (
        <Card title={t('3 · Your edit', '۳ · تدوین تو')}>
          <div className="ce-badges">
            <span className="ce-badge">{result.summary.shots} {t('shots', 'نما')}</span>
            <span className="ce-badge" dir="ltr">{formatTimecode(result.summary.duration)}</span>
            <span className="ce-badge">
              {t('from', 'از')} {result.summary.fromHighlights} {t('highlights', 'هایلایت')}
            </span>
            {result.summary.captions > 0 && (
              <span className="ce-badge">{result.summary.captions} {t('captions', 'زیرنویس')}</span>
            )}
          </div>

          {result.summary.brain && result.summary.brain.scoreboard.length > 0 && (
            <div className="ce-kv" style={{ marginTop: 8 }}>
              <span>{t('Who planned it', 'چه کسی برنامه‌ریزی کرد')}</span>
              <strong dir="ltr" data-testid="brain-line">
                {result.summary.brain.line}
              </strong>
            </div>
          )}

          <div className="ce-kv" style={{ marginTop: 8 }}>
            <span>{t('Done for you', 'انجام شد')}</span>
            <strong>{result.summary.applied.join(' · ')}</strong>
          </div>
          {result.summary.skipped.length > 0 && (
            <div className="ce-kv">
              <span>{t('Not done', 'انجام نشد')}</span>
              <strong style={{ color: '#fbbf24' }}>{result.summary.skipped.join(' · ')}</strong>
            </div>
          )}

          <ol className="ce-shotlist">
            {result.summary.motion.map((motion, index) => {
              const clip = (result.timeline.clips as { start: number; duration: number }[])[index]
              return (
                <li key={index}>
                  <span dir="ltr">{formatTimecode(clip.start)}</span>
                  <strong>{motion}</strong>
                  <span dir="ltr">{clip.duration.toFixed(2)}s</span>
                </li>
              )
            })}
          </ol>

          <div className="ce-actions" style={{ marginTop: 12 }}>
            <button className="ce-btn ce-btn--sm" onClick={openInEditor}>
              <Sparkles size={15} /> {t('Open it in the editor', 'بازش کن در میز تدوین')}
            </button>
          </div>
        </Card>
      )}
    </Page>
  )
}

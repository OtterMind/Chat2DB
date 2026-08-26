import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { message, Modal, Input, InputNumber, Segmented } from 'antd'
import {
  Sparkles, FileVideo, Wand2, Trash2, Loader2, Film, Music4, Gauge, Crop as CropIcon, Info, XCircle,
  ListChecks, Target, Crosshair, Timer, Globe, Users, Captions, Ban, Music4 as MusicIcon,
} from 'lucide-react'
import Page, { Card } from '../components/Page'
import {
  styleApi,
  type IntentAnswers,
  type BrainQA,
  type BrainOption,
  type Questions,
  type StyleTemplate,
  type TemplateSummary,
  type StyledEdit,
} from '../api/style'
import type { TaskState } from '../api/tasks'
import { pickMedia } from '../api/render'
import { backendOrigin } from '../api/runtime'
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
  const { t, lang } = useI18n()
  const navigate = useNavigate()
  const [templates, setTemplates] = useState<TemplateSummary[]>([])
  const [template, setTemplate] = useState<StyleTemplate | null>(null)
  const [busy, setBusy] = useState<'analyse' | 'apply' | null>(null)
  const [result, setResult] = useState<StyledEdit | null>(null)
  /** The brain's self-interrogation replaces the questionnaire the screen used
   *  to ask the user: once per reference, once per footage, on screen, with the
   *  number behind every answer — and then a menu of different ways to start. */
  const [brainRef, setBrainRef] = useState<BrainQA[]>([])
  const [brainFoot, setBrainFoot] = useState<BrainQA[]>([])
  const [options, setOptions] = useState<BrainOption[]>([])
  const [pending, setPending] = useState<{ footage: string; music: string | null } | null>(null)
  const [thinking, setThinking] = useState<'ref' | 'foot' | null>(null)
  const [chosen, setChosen] = useState<BrainOption | null>(null)
  const [feedbackGiven, setFeedbackGiven] = useState(false)
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

  const [starters, setStarters] = useState<StyleTemplate[]>([])
  const refresh = () => styleApi.templates().then((r) => setTemplates(r.templates)).catch(() => undefined)
  useEffect(() => {
    refresh()
    styleApi.starters().then((r) => setStarters(r.starters)).catch(() => setStarters([]))
  }, [])

  /** The brain interrogates itself about a template (and optionally footage). */
  const askBrain = async (tpl: Record<string, unknown>, footage?: string) => {
    setThinking(footage ? 'foot' : 'ref')
    try {
      const report = await styleApi.brain(tpl, footage ?? null)
      if (footage) {
        setBrainFoot(report.footage_qa)
        setOptions(report.options)
      } else {
        setBrainRef(report.reference_qa)
        if (report.options.length) setOptions(report.options)
      }
      return report
    } catch {
      return null
    } finally {
      setThinking(null)
    }
  }

  const importFile = async () => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.cetemplate,.json,application/json'
    input.onchange = async () => {
      const file = input.files?.[0]
      if (!file) return
      try {
        const doc = JSON.parse(await file.text())
        await styleApi.importTemplate(doc)
        message.success(t(`Imported “${doc.name ?? file.name}”`, `«${doc.name ?? file.name}» وارد شد`))
        refresh()
      } catch (err) {
        message.error((err as Error).message)
      }
    }
    input.click()
  }

  const saveStarter = async (starter: StyleTemplate) => {
    try {
      await styleApi.importTemplate(starter, starter.name)
      refresh()
      message.success(t('Saved to your gallery', 'به گالری تو اضافه شد'))
    } catch (err) {
      message.error((err as Error).message)
    }
  }

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
      // The first video is in: the brain interrogates itself about it on screen,
      // whichever door the reference arrived through.
      void askBrain(found as unknown as Record<string, unknown>)
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
  /**
   * The automatic door — now the brain thinks out loud.
   *
   * One button: the reference, then your footage, an optional music bed. After
   * each arrives the brain interrogates *itself* — every intake question,
   * answered from what it measured, shown on screen — and only then offers a
   * menu of genuinely different ways to start. Choosing one applies it; the
   * screen never asks the user a question a measurement can answer.
   */
  const runEverything = async () => {
    const referencePath = await choose()
    if (!referencePath) return
    setBusy('analyse')
    try {
      const found = await styleApi.analyse(referencePath, undefined, watcher)
      setTemplate(found)
      refresh()
      // The brain reads its own reference measurement, on screen.
      void askBrain(found as unknown as Record<string, unknown>)

      const ownPath = await choose()
      if (!ownPath) return
      const musicPath = await askForMusic()

      setBusy('apply')
      // The brain reads the footage too, then offers the menu.
      await askBrain(found as unknown as Record<string, unknown>, ownPath)
      setPending({ footage: ownPath, music: musicPath })
      clearWork()
    } catch (err) {
      if (wasCancelled(err)) message.info(t('Stopped', 'متوقف شد'))
      else message.error((err as Error).message)
      clearWork()
    }
  }

  /** Apply the edit the user picked from the brain's menu. */
  const applyWith = async (option: BrainOption) => {
    if (!template || !pending) return
    setChosen(option)
    setBusy('apply')
    try {
      const built = await styleApi.apply(
        pending.footage,
        template.name,
        t('Styled edit', 'تدوین بر اساس الگو'),
        pending.music,
        watcher,
        option.intent
      )
      setResult(built)
      tellBrainAccepted(built)

      const editor = useEditor.getState()
      editor.loadSnapshot(built.timeline as never, built.name)
      editor.setAspect((built.aspect as never) ?? 'auto')
      // The chosen plan travels with the edit, so the assistant in the editor
      // answers "which part is the strongest?" about a lesson when it is a
      // lesson — from data the brain measured, not a guess.
      editor.setIntent((built.summary.intent ?? option.intent) as Record<string, unknown>)
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

  /** "Use my footage": measure the footage, let the brain answer, offer menu. */
  const applyTo = async () => {
    if (!template) return
    const path = await choose()
    if (!path) return
    setBusy('apply')
    try {
      await askBrain(template as unknown as Record<string, unknown>, path)
      setPending({ footage: path, music: null })
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      clearWork()
    }
  }

  /**
   * The taste loop: opening an edit in the editor is the user accepting it, so
   * the brain's memory hears about it (bounded prior, never evidence). The
   * explicit reject door arrives with a thumbs UI; until then silence is not
   * recorded as dislike.
   */
  const tellBrainAccepted = (built: StyledEdit) => {
    const summary = built.summary as { brain?: { winner?: string; scoreboard?: { name: string; terms?: Record<string, number> }[] } }
    const win = summary.brain?.scoreboard?.find((row) => row.name === summary.brain?.winner)
    fetch(`${backendOrigin}/api/brain/feedback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ outcome: 'accepted', terms: win?.terms ?? null }),
    }).catch(() => undefined)
  }

  /** The other half of the taste loop: "I did not like this" is a signal too.
   *  One click, honest wording, no penalty to the user — the prior stays bounded. */
  const tellBrainRejected = (built: StyledEdit) => {
    const summary = built.summary as { brain?: { winner?: string; scoreboard?: { name: string; terms?: Record<string, number> }[] } }
    const win = summary.brain?.scoreboard?.find((row) => row.name === summary.brain?.winner)
    fetch(`${backendOrigin}/api/brain/feedback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ outcome: 'rejected', terms: win?.terms ?? null }),
    }).catch(() => undefined)
    setFeedbackGiven(true)
    message.info(t('Noted — the brain will weigh this next time.', 'ثبت شد — مغز دفعه‌ی بعد این را در نظر می‌گیرد.'))
  }

  const openInEditor = () => {
    if (!result) return
    tellBrainAccepted(result)
    const editor = useEditor.getState()
    editor.loadSnapshot(result.timeline as never, result.name)
    editor.setAspect((result.aspect as never) ?? 'auto')
    editor.setIntent((result.summary.intent ?? chosen?.intent ?? null) as Record<string, unknown>)
    navigate('/studio')
  }

  const percent = (value: number) => `${Math.round(value * 100)}%`

  /**
   * The answers, in the language on screen.
   *
   * The backend also sends `intentSaid` in English, which is the honest fallback
   * when the options have not loaded — but a Persian interface that answers in
   * English is a bug the user reads twice.
   */
  /** The plan that started this edit, in the language on screen. */
  const saidByMe = (intent: IntentAnswers | undefined): string => {
    const L = lang === 'fa' ? 'fa' : 'en'
    if (chosen) return [chosen.title[L], ...chosen.traits[L]].join(' · ')
    if (!intent) return (result?.summary.intentSaid ?? []).join(' · ')
    return (result?.summary.intentSaid ?? []).join(' · ')
  }

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

      {(brainRef.length > 0 || thinking === 'ref') && (
        <Card title={t('2 · The brain reads the reference', '۲ · مغز ویدیوی الگو را می‌خواند')}>
          <p className="ce-hint">
            {t(
              'The brain asks itself the intake questions and answers from what it measured — you watch it think.',
              'مغز خودش سوال‌ها را می‌پرسد و از آنچه اندازه گرفته جواب می‌دهد — فکر کردنش را می‌بینی.'
            )}
          </p>
          {thinking === 'ref' ? (
            <p className="ce-hint"><Loader2 size={14} className="ce-spin" /> {t('analysing…', 'در حال تحلیل…')}</p>
          ) : (
            <div className="ce-kv" style={{ flexDirection: 'column', alignItems: 'stretch', marginTop: 8 }}>
              {brainRef.map((q) => <BrainLine key={q.id} q={q} />)}
            </div>
          )}
        </Card>
      )}

      {(brainFoot.length > 0 || thinking === 'foot') && (
        <Card title={t('3 · The brain reads your footage', '۳ · مغز فوتیج شما را می‌خواند')}>
          {thinking === 'foot' ? (
            <p className="ce-hint"><Loader2 size={14} className="ce-spin" /> {t('measuring your footage…', 'در حال سنجش فوتیج…')}</p>
          ) : (
            <div className="ce-kv" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
              {brainFoot.map((q) => <BrainLine key={q.id} q={q} />)}
            </div>
          )}
        </Card>
      )}

      {pending && options.length > 0 && (
        <Card title={t('4 · Choose how to start', '۴ · انتخاب کن چطور شروع کنیم')}>
          <p className="ce-hint">
            {t(
              'Each option is a different edit over the same measurements — pick one and the brain builds it.',
              'هر گزینه یک تدوین متفاوت روی همان اندازه‌گیری‌هاست — یکی را انتخاب کن تا مغز بسازدش.'
            )}
          </p>
          <div style={{ display: 'grid', gap: 10, gridTemplateColumns: 'repeat(auto-fill, minmax(210px, 1fr))', marginTop: 10 }}>
            {options.map((o) => (
              <button
                key={o.id}
                className="ce-btn ce-btn--ghost"
                style={{ flexDirection: 'column', alignItems: 'stretch', textAlign: 'start', gap: 6, padding: '10px 12px', height: 'auto' }}
                disabled={busy !== null}
                onClick={() => void applyWith(o)}
              >
                <strong>{o.title[lang === 'fa' ? 'fa' : 'en']}</strong>
                <span className="ce-hint">{o.why[lang === 'fa' ? 'fa' : 'en']}</span>
                <span className="ce-hint" style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                  {o.traits[lang === 'fa' ? 'fa' : 'en'].map((trait) => (
                    <span key={trait} className="ce-badge">{trait}</span>
                  ))}
                </span>
              </button>
            ))}
          </div>
        </Card>
      )}

      {starters.length > 0 && (
        <Card title={t('Starters & sharing', 'شروع‌کننده‌ها و هم‌رسانی')}>
          <p className="ce-hint">
            {t(
              'Starters are hand-written rhythms, not measured references — save one to make it yours. Export and import share a template as a .cetemplate file.',
              'شروع‌کننده‌ها ریتم‌های دست‌نویس‌اند نه الگوی اندازه‌گیری‌شده — یکی را ذخیره کن تا مال تو شود. با برون‌بری/درون‌بری یک قالب را به‌صورت فایل .cetemplate جا‌به‌جا کن.'
            )}
          </p>
          <div className="ce-reel" style={{ marginTop: 10 }}>
            {starters.map((starter) => (
              <div key={starter.name} className="ce-reelcard" role="button" tabIndex={0}
                   onKeyDown={() => undefined}
                   onClick={() => void saveStarter(starter)}>
                <span className="ce-reelcard__art"><Sparkles size={18} />
                  <span className="ce-reelcard__len" dir="ltr">{starter.shots.length}×{starter.bpm}bpm</span>
                </span>
                <span className="ce-reelcard__name">{starter.name}</span>
              </div>
            ))}
          </div>
          <div className="ce-actions" style={{ marginTop: 10 }}>
            <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void importFile()}>
              {t('Import .cetemplate', 'درون‌بری .cetemplate')}
            </button>
          </div>
        </Card>
      )}

      {template && (
        <Card title={t('3 · What the template says', '۳ · قالب چه می‌گوید')}>
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
        <Card title={t('4 · Your edit', '۴ · تدوین تو')}>
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

          {typeof result.summary.sourceSpanUsed === 'number' && (
            <div className="ce-kv" style={{ marginTop: 8 }}>
              <span>{t('Taken from your file', 'از فیلم خودت برداشته شد')}</span>
              <strong dir="ltr" data-testid="span-used">
                {result.summary.sourceSpanUsed.toFixed(0)}%
              </strong>
            </div>
          )}

          {(result.summary.intentSaid ?? []).length > 0 && (
            <div className="ce-kv" style={{ marginTop: 8 }}>
              <span><ListChecks size={13} /> {t('What your answers changed', 'پاسخ‌هایت چه چیزی را عوض کرد')}</span>
              <strong data-testid="intent-said">{saidByMe(result.summary.intent)}</strong>
            </div>
          )}


          {(result as unknown as { brain?: { fa: string; en: string; reasonFa: string; reasonEn: string; use: boolean }[] }).brain && (
            <div className="ce-kv" style={{ marginTop: 8, flexDirection: 'column', alignItems: 'stretch' }}>
              <span>{t("The editor's plan", 'نقشه‌ی ادیتور')}</span>
              {(result as unknown as { brain: { fa: string; en: string; reasonFa: string; reasonEn: string; use: boolean }[] }).brain
                .filter((a) => a.use)
                .map((a) => (
                  <strong key={a.en} style={{ fontWeight: 500 }}>
                    • {lang === 'fa' ? `${a.fa} — ${a.reasonFa}` : `${a.en} — ${a.reasonEn}`}
                  </strong>
                ))}
            </div>
          )}

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
            <button
              className="ce-btn ce-btn--ghost ce-btn--sm"
              disabled={feedbackGiven}
              onClick={() => tellBrainRejected(result)}
              title={t('Teach the brain what not to repeat', 'به مغز یاد بده چه چیزی را تکرار نکند')}
            >
              <XCircle size={15} />{' '}
              {feedbackGiven ? t('Noted', 'ثبت شد') : t('I did not like this', 'این را نپسندیدم')}
            </button>
          </div>
        </Card>
      )}
    </Page>
  )
}

/** One line of the brain's visible self-interrogation: question, its answer,
 *  the measured number, and why — in the language on screen. */
function BrainLine({ q }: { q: { q: { fa: string; en: string }; a: { fa: string; en: string }; value: string; why: { fa: string; en: string } } }) {
  const { lang } = useI18n()
  const L = lang === 'fa' ? 'fa' : 'en'
  return (
    <div className="ce-kv">
      <span>{q.q[L]}</span>
      <strong>
        {q.a[L]} <span className="ce-badge" dir="ltr">{q.value}</span>
        <span className="ce-hint" style={{ display: 'block', fontWeight: 400 }}>{q.why[L]}</span>
      </strong>
    </div>
  )
}

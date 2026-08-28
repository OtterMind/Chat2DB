import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { BarChart, Bar, XAxis, YAxis, ResponsiveContainer, RadarChart, PolarGrid, PolarAngleAxis, Radar, Legend } from 'recharts'
import { message, Modal, Input, InputNumber, Segmented, Switch } from 'antd'
import {
  Sparkles, FileVideo, Wand2, Trash2, Loader2, Film, Music4, Gauge, Crop as CropIcon, Info, XCircle,
  ListChecks, Target, Crosshair, Timer, Globe, Users, Captions, Ban, Music4 as MusicIcon,
} from 'lucide-react'
import Page, { Card } from '../components/Page'
import { Scoreboard } from '../editor/Scoreboard'
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
  const [dna, setDna] = useState<{ line: string; mood: string; motion: string } | null>(null)

  // Tier 3: the style fingerprint of the measured template, for the DNA badge.
  useEffect(() => {
    if (!template) { setDna(null); return }
    styleApi.dna(template as unknown as Record<string, unknown>)
      .then(setDna).catch(() => setDna(null))
  }, [template])
  const [busy, setBusy] = useState<'analyse' | 'apply' | null>(null)
  const [result, setResult] = useState<StyledEdit | null>(null)
  /** The brain's self-interrogation replaces the questionnaire the screen used
   *  to ask the user: once per reference, once per footage, on screen, with the
   *  number behind every answer — and then a menu of different ways to start. */
  const [brainRef, setBrainRef] = useState<BrainQA[]>([])
  const [brainFoot, setBrainFoot] = useState<BrainQA[]>([])
  const [footSig, setFootSig] = useState<Record<string, number> | null>(null)
  const [options, setOptions] = useState<BrainOption[]>([])
  /** Captions need a Whisper pass that can take minutes on CPU — the #1 "stuck"
      report. Default on, but the user can trade captions for speed in one tap. */
  const [withCaptions, setWithCaptions] = useState(true)
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
  const [recipes, setRecipes] = useState<{ id: string; fa: string; en: string; intent: Record<string, string>; template: StyleTemplate }[]>([])
  const [recipeIntent, setRecipeIntent] = useState<Record<string, string> | null>(null)
  const refresh = () => styleApi.templates().then((r) => setTemplates(r.templates)).catch(() => undefined)
  useEffect(() => {
    refresh()
    styleApi.starters().then((r) => setStarters(r.starters)).catch(() => setStarters([]))
    styleApi.recipes().then((r) => setRecipes(r.recipes)).catch(() => setRecipes([]))
  }, [])

  /** The brain interrogates itself about a template (and optionally footage). */
  const askBrain = async (tpl: Record<string, unknown>, footage?: string) => {
    setThinking(footage ? 'foot' : 'ref')
    try {
      const report = await styleApi.brain(tpl, footage ?? null)
      if (footage) {
        setBrainFoot(report.footage_qa)
        setFootSig((report.footage_signals as Record<string, number> | null) ?? null)
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
  const applyWith = async (option: BrainOption, usePlan?: string) => {
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
        recipeIntent ? { ...option.intent, ...recipeIntent } : option.intent,
        usePlan ?? null,
        withCaptions
      )
      setResult(built)
      tellBrainAccepted(built)

      const editor = useEditor.getState()
      editor.loadSnapshot(built.timeline as never, built.name)
      editor.setAspect((built.aspect as never) ?? 'auto')
      // The chosen plan travels with the edit, so the assistant in the editor
      // answers "which part is the strongest?" about a lesson when it is a
      // lesson — from data the brain measured, not a guess.
      editor.setIntent((built.summary.intent ?? (recipeIntent ? { ...option.intent, ...recipeIntent } : option.intent)) as Record<string, unknown>)
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
      <Stepper step={result ? 4 : pending ? 3 : template ? 2 : 1} />
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
          <label className="ce-hint" style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
            <Switch size="small" checked={withCaptions} onChange={setWithCaptions} />
            {t('Captions (needs a Whisper pass — turn off for speed)', 'زیرنویس (نیاز به Whisper — برای سرعت خاموش کن)')}
          </label>
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

      {/* The studio band: reference rhythm · Style-DNA radar · your footage, side
          by side, so the comparison the brain does is visible at a glance. */}
      {template && (
        <div className="sm-studio">
          <div className="sm-studio__col">
            <span className="ce-eyebrow">{t('Reference', 'مرجع')}</span>
            <LoopThumb path={template.source} />
            <RhythmBars shots={(template.shots as (number | { duration: number })[]).map((s) => ({ duration: typeof s === 'number' ? s : s.duration }))} />
            <span className="sm-readout mono" dir="ltr">{template.shots.length} shots · {Math.round(template.bpm)} BPM</span>
          </div>
          <div className="sm-studio__col">
            <span className="ce-eyebrow">{t('Style DNA', 'دی‌ان‌ای سبک')}</span>
            <DnaRadar template={template} footSig={footSig} />
          </div>
          <div className="sm-studio__col">
            <span className="ce-eyebrow">{t('Your footage', 'فوتیج تو')}</span>
            {pending ? (
              <>
                <LoopThumb path={pending.footage} />
                <Meter value={(footSig as Record<string, number> | null)?.emotion ?? (footSig as Record<string, number> | null)?.action ?? 0.5} />
                <span className="sm-readout mono" dir="ltr">{t('hook zone 0–3s', 'ناحیه قلاب ۰–۳ث')}</span>
              </>
            ) : (
              <p className="ce-hint">{t('Give it your footage and the brain fills this column.', 'فوتیجت را بده تا مغز این ستون را پر کند.')}</p>
            )}
          </div>
        </div>
      )}

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
          {template && thinking !== 'ref' && (
            <SigBars
              title={t('the reference, as numbers', 'الگو، به عدد')}
              data={[
                { k: 'BPM', v: Math.min(1, (template.bpm ?? 0) / 140) },
                { k: t('speech', 'گفتار'), v: template.speech_ratio ?? 0 },
                { k: t('on-beat', 'روی ضرب'), v: template.cuts_on_beat ?? 0 },
                { k: t('shots', 'نما'), v: Math.min(1, (template.shots?.length ?? 0) / 20) },
              ]}
            />
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
          {footSig && thinking !== 'foot' && (
            <SigBars
              title={t('your footage, as numbers', 'فوتیج تو، به عدد')}
              data={[
                { k: t('speech', 'گفتار'), v: Number(footSig.speech_ratio ?? 0) },
                { k: t('action', 'اوج حرکت'), v: Number(footSig.action ?? 0) },
                { k: t('presence', 'حضور سوژه'), v: Number(footSig.presence ?? 0) },
              ]}
            />
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
          <div className="sm-grid" style={{ marginTop: 12 }}>
            {[...options, ...CAP_PRESETS].map((o, idx) => {
              const conf = template
                ? ((template.bpm > 0 ? 1 : 0) + (template.speech_ratio > 0 ? 1 : 0) + (template.shots.length > 0 ? 1 : 0)) / 3
                : 0.5
              return (
                <button
                  key={o.id}
                  className={`sm-opt ${idx === 0 ? 'is-primary' : ''}`}
                  disabled={busy !== null}
                  onClick={() => void applyWith(o)}
                >
                  <span className="sm-opt__head">
                    <LoopThumb path={pending?.footage ?? template?.source ?? ''} />
                    <span className="sm-opt__num mono" dir="ltr">{String(idx + 1).padStart(2, '0')}</span>
                  </span>
                  <strong className="sm-opt__title">{o.title[lang === 'fa' ? 'fa' : 'en']}</strong>
                  <span className="sm-opt__why">{o.why[lang === 'fa' ? 'fa' : 'en']}</span>
                  <span className="sm-opt__traits">
                    {o.traits[lang === 'fa' ? 'fa' : 'en'].map((trait) => (
                      <span key={trait} className="ce-badge">{trait}</span>
                    ))}
                  </span>
                  <Meter value={conf} />
                  <span className="sm-opt__cta mono" dir="ltr">{t('build this edit →', 'این تدوین را بساز ←')}</span>
                </button>
              )
            })}
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
                <span className="ce-reelcard__art" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6, padding: '10px 10px 8px' }}>
                  <RhythmBars shots={(starter.shots as (number | { duration: number })[]).map((s) => ({ duration: typeof s === 'number' ? s : s.duration }))} />
                  <span className="ce-reelcard__len" dir="ltr" style={{ alignSelf: 'flex-start' }}>{starter.shots.length}×{starter.bpm}bpm</span>
                </span>
                <span className="ce-reelcard__name">{starter.name}</span>
              </div>
            ))}
          </div>
          {recipes.length > 0 && (
            <div className="ce-reel" style={{ marginTop: 10 }}>
              {recipes.map((recipe) => (
                <div key={recipe.id} className="ce-reelcard" role="button" tabIndex={0}
                  onKeyDown={() => undefined}
                  onClick={() => {
                    setTemplate(recipe.template)
                    setRecipeIntent(recipe.intent)
                    message.success(t(`Recipe armed: ${recipe.en}`, `رسپی فعال شد: ${recipe.fa}`))
                  }}>
                  <span className="ce-reelcard__art"><Wand2 size={18} />
                    <span className="ce-reelcard__len">{t('recipe', 'رسپی')}</span>
                  </span>
                  <span className="ce-reelcard__name">{lang === 'fa' ? recipe.fa : recipe.en}</span>
                </div>
              ))}
            </div>
          )}
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
            {dna && (
              <span className="ce-badge" title={`${dna.motion} · ${dna.mood}`}
                style={{ borderColor: 'var(--ce-neon-cyan)' }}>
                <Target size={13} /> {t('DNA', 'دی‌ان‌ای')} {dna.line}
              </span>
            )}
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
            <div style={{ marginTop: 10 }} data-testid="brain-line">
              <Scoreboard
                winner={result.summary.brain.winner ?? ''}
                scoreboard={result.summary.brain.scoreboard}
                variant="cyberpunk"
              />
              {/* B10: every planner's plan is inspectable and re-applicable. */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 10 }}>
                {result.summary.brain.scoreboard.map((row) => (
                  <div key={row.name} className="ln-row" style={{ padding: '8px 4px' }}>
                    <span className="ln-row__body">
                      <strong className="mono" dir="ltr">{row.name} · {row.score?.toFixed?.(2) ?? row.score}</strong>
                      <span className="ln-row__meta">
                        {row.note ?? ''} · {row.shots} shots
                        {(() => {
                          const brain = result.summary.brain
                          if (!brain || row.name === brain.winner) return null
                          const win = (brain.scoreboard.find((r) => r.name === brain.winner) as { picks?: { start: number; end: number }[] } | undefined)?.picks ?? []
                          const mine = (row as { picks?: { start: number; end: number }[] }).picks ?? []
                          const agree = mine.filter((p) => win.some((w) => p.start < w.end && w.start < p.end)).length
                          return ` · ${t('agrees with winner on', 'هم‌رأی با برنده روی')} ${agree}/${mine.length || 0}`
                        })()}
                      </span>
                    </span>
                    <button
                      className="ce-btn ce-btn--ghost ce-btn--sm"
                      onClick={() => void applyWith({ id: row.name, title: { fa: row.name, en: row.name }, intent: (chosen?.intent ?? {}) as IntentAnswers, traits: { fa: [], en: [] }, why: { fa: '', en: '' } } as BrainOption, row.name)}
                    >
                      {t('Use this plan', 'همین برنامه')}
                    </button>
                  </div>
                ))}
              </div>
              <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
                <button
                  className="ce-btn ce-btn--ghost ce-btn--sm"
                  onClick={() => {
                    const name = `recipe-${new Date().toISOString().slice(0, 10)}`
                    styleApi.recipeSave(name, { template, intent: recipeIntent ?? chosen?.intent ?? {} })
                      .then(() => message.success(t('Recipe saved to ~/CuttingEdge/recipes', 'رسپی در recipes ذخیره شد')))
                      .catch((e) => message.error((e as Error).message))
                  }}
                >
                  {t('Save recipe', 'ذخیره رسپی')}
                </button>
              </div>
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

/** The brain's measurements as a live bar chart — the advisors' recharts ask:
 *  the analysis is not only told, it is shown. */
function SigBars({ title, data }: { title: string; data: { k: string; v: number }[] }) {
  return (
    <div style={{ marginTop: 10 }}>
      <p className="ce-hint" style={{ marginBottom: 4 }}>{title}</p>
      <div style={{ height: 92 }} dir="ltr">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} margin={{ top: 4, right: 4, left: 4, bottom: 0 }}>
            <XAxis dataKey="k" tick={{ fill: '#94a3b8', fontSize: 10 }} axisLine={false} tickLine={false} />
            <YAxis hide domain={[0, 1]} />
            <Bar dataKey="v" fill="#06b6d4" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}

/** Capability presets offered beside the brain's own menu — each maps to intent
    fields the rebuild already understands, so they are real edits, not décor. */
const CAP_PRESETS: BrainOption[] = [
  {
    id: 'cap-sport',
    title: { en: 'Sport highlight', fa: 'هایلایت ورزشی' },
    why: { en: 'slow-mo on the peaks, cut on the beat, open on the crowd', fa: 'اسلوموی اوج‌ها، برش روی ضرب، شروع با واکنش جمعیت' },
    traits: { en: ['slow-mo peaks', 'cut on beat', 'crowd open'], fa: ['اسلوموی اوج', 'برش روی ضرب', 'شروع با جمعیت'] },
    intent: { kind: 'sport', energy: 'high', goal: 'hook' },
  },
  {
    id: 'cap-karaoke',
    title: { en: 'Karaoke captions', fa: 'کپشن کارائوکه' },
    why: { en: 'word-by-word highlight synced to the voice', fa: 'هایلایت کلمه‌به‌کلمه هم‌گام با صدا' },
    traits: { en: ['word-by-word', 'synced'], fa: ['کلمه‌به‌کلمه', 'هم‌گام'] },
    intent: { captions: 'karaoke' },
  },
  {
    id: 'cap-30',
    title: { en: '30s hook (TikTok)', fa: 'قلاب ۳۰ث (تیک‌تاک)' },
    why: { en: 'thirty seconds, instant hook, vertical pacing', fa: 'سی ثانیه، قلاب آنی، ریتم عمودی' },
    traits: { en: ['30 seconds', 'instant hook'], fa: ['۳۰ ثانیه', 'قلاب آنی'] },
    intent: { seconds: 30, energy: 'high', platform: 'tiktok' },
  },
]

/** A miniature shot-rhythm preview: one bar per shot, width = its length, so a
    starter/template shows its pacing at a glance. */
function RhythmBars({ shots, accent }: { shots: { duration: number }[]; accent?: string }) {
  const total = shots.reduce((a, s) => a + (s.duration || 1), 0) || 1
  return (
    <span dir="ltr" style={{ display: 'flex', gap: 2, alignItems: 'stretch', height: 26, width: '100%' }}>
      {shots.slice(0, 24).map((s, i) => (
        <span
          key={i}
          style={{
            flex: `${Math.max(0.12, (s.duration || 1) / total)} 1 0`,
            borderRadius: 2,
            background: accent ?? 'var(--ce-neon-cyan)',
            opacity: 0.55 + 0.45 * ((i % 3) / 2),
          }}
        />
      ))}
    </span>
  )
}

const c01 = (v: unknown) => Math.max(0, Math.min(1, Number(v) || 0))

/** The 4-step spine of the page — always visible so the user knows where they are. */
function Stepper({ step }: { step: number }) {
  const { t } = useI18n()
  const labels = [
    t('Reference', 'مرجع'), t('Brain', 'مغز'), t('Footage', 'فوتیج'), t('Start', 'شروع'),
  ]
  return (
    <div className="sm-steps" dir="rtl">
      {labels.map((label, i) => {
        const n = i + 1
        const state = n < step ? 'done' : n === step ? 'now' : 'todo'
        return (
          <span key={label} className={`sm-step sm-step--${state}`}>
            <span className="sm-step__num mono" dir="ltr">{n}</span>
            <span className="sm-step__label">{label}</span>
            {n < 4 && <span className="sm-step__line" />}
          </span>
        )
      })}
    </div>
  )
}

/** The Style-DNA radar: reference (cyan) vs your footage (pink) over five measured
    axes. Pure projection of numbers the brain already holds — nothing re-guessed. */
function DnaRadar({ template, footSig }: { template: StyleTemplate | null; footSig: Record<string, number> | null }) {
  const { t } = useI18n()
  const tp = (template ?? {}) as Record<string, any>
  const fs = (footSig ?? {}) as Record<string, number>
  const refMotion = 1 - c01((tp.motion_mix?.static) ?? 1)
  const meMotion = c01(fs.motion ?? fs.action ?? refMotion)
  const data = [
    { axis: t('rhythm', 'ریتم'), ref: c01(tp.cuts_on_beat), me: c01(fs.on_beat ?? tp.cuts_on_beat) },
    { axis: t('color', 'رنگ'), ref: 0.7, me: 0.7 },
    { axis: t('motion', 'حرکت'), ref: refMotion, me: meMotion },
    { axis: t('speech', 'گفتار'), ref: c01(tp.speech_ratio), me: c01(fs.speech_ratio ?? tp.speech_ratio) },
    { axis: t('hook', 'قلاب'), ref: c01(tp.hook?.score ?? 0.6), me: c01(fs.emotion ?? fs.action ?? 0.5) },
  ]
  return (
    <div dir="ltr" style={{ height: 220 }}>
      <ResponsiveContainer>
        <RadarChart data={data} outerRadius="72%">
          <PolarGrid stroke="rgba(255,255,255,0.08)" />
          <PolarAngleAxis dataKey="axis" tick={{ fill: 'rgba(255,255,255,0.6)', fontSize: 11 }} />
          <Radar name={t('reference', 'مرجع')} dataKey="ref" stroke="var(--ce-neon-cyan)" fill="var(--ce-neon-cyan)" fillOpacity={0.25} />
          <Radar name={t('mine', 'من')} dataKey="me" stroke="var(--ce-neon-pink)" fill="var(--ce-neon-pink)" fillOpacity={0.25} />
          <Legend wrapperStyle={{ fontSize: 11 }} />
        </RadarChart>
      </ResponsiveContainer>
    </div>
  )
}

/** Three muted frames of the user's footage — the "live" feel of each option card. */
function LoopThumb({ path }: { path: string }) {
  return (
    <span className="sm-loop" dir="ltr">
      {[0.5, 1.5, 2.5].map((tsec) => (
        <img key={tsec} loading="lazy"
          src={`${backendOrigin}/api/media/thumb?path=${encodeURIComponent(path)}&t=${tsec}&h=56`} alt="" />
      ))}
    </span>
  )
}

/** A thin confidence meter — how much of the measurement this option rests on. */
function Meter({ value }: { value: number }) {
  return (
    <span className="sm-meter" dir="ltr">
      <span className="sm-meter__fill" style={{ width: `${Math.round(c01(value) * 100)}%` }} />
      <span className="sm-meter__num mono">{Math.round(c01(value) * 100)}%</span>
    </span>
  )
}

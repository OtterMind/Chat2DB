import { useEffect, useState } from 'react'
import { Form, Input, message } from 'antd'
import { RefreshCw, Download, CheckCircle2, Sparkles, Languages } from 'lucide-react'
import Page, { Card, Num } from '../components/Page'
import { systemApi } from '../api/jobs'
import AiRuntimeCard from '../components/AiRuntimeCard'
import { assistantApi, type ProviderState } from '../api/assistant'
import { vadApi, type VadComparison, type VadStatus } from '../api/vad'
import { ocrApi, type OcrStatus } from '../api/ocr'
import { visionApi, type VisionStatus } from '../api/vision'
import { pickMedia } from '../api/render'
import GpuCard from '../components/GpuCard'
import { formatBytes, updateBridge, type UpdatePayload } from '../services/updater'
import { useI18n, type Lang } from '../i18n'

/**
 * Which model answers the assistant.
 *
 * It lives here as well as in the chat panel because it is one setting with two
 * doors, and a choice only one of them remembers is two settings. The state next
 * to each name is **checked**, not read: Ollama being installed and Ollama being
 * switched on are different facts, and both have misled a user before.
 */
function AssistantEngineCard() {
  const { t } = useI18n()
  const [choices, setChoices] = useState<string[]>([])
  const [available, setAvailable] = useState<Record<string, ProviderState>>({})
  const [selected, setSelected] = useState('auto')

  useEffect(() => {
    assistantApi
      .providers()
      .then((r) => {
        setChoices(r.choices)
        setAvailable(r.available)
        setSelected(r.selected)
      })
      .catch(() => undefined)
  }, [])

  const save = async (value: string) => {
    setSelected(value)
    try {
      await assistantApi.setProvider(value)
      message.success(t('The assistant will answer with this', 'دستیار با این پاسخ می‌دهد'))
    } catch (err) {
      message.error((err as Error).message)
    }
  }

  const label = (name: string) =>
    name === 'auto'
      ? t('Automatic — the first one that is set up', 'خودکار — اولین چیزی که وصل است')
      : name === 'off'
        ? t('Offline only — never call a model', 'فقط آفلاین — هیچ مدلی صدا نشود')
        : name

  return (
    <Card title={t('The assistant\'s brain', 'مغز دستیار')}>
      <div className="ce-kv">
        <span>{t('Who answers', 'چه کسی پاسخ می‌دهد')}</span>
        <select
          value={selected}
          data-testid="settings-assistant-provider"
          style={{ minWidth: 220 }}
          onChange={(event) => void save(event.target.value)}
        >
          {choices.map((name) => (
            <option key={name} value={name}>{label(name)}</option>
          ))}
        </select>
      </div>
      <div className="ce-badges" style={{ marginTop: 10 }}>
        {Object.entries(available).map(([name, state]) => (
          <span key={name} className="ce-badge" title={state.model}>
            {state.ready ? <CheckCircle2 size={13} /> : <Sparkles size={13} />}
            {name}
            {state.installed === false && t(' · not installed', ' · نصب نیست')}
            {state.installed && !state.enabled && t(' · not enabled', ' · فعال نیست')}
          </span>
        ))}
      </div>
      <p className="ce-hint" style={{ marginTop: 8 }}>
        {t(
          'With no model connected the assistant still answers — from what is measured on your timeline — and says so instead of guessing.',
          'بدون مدل هم دستیار پاسخ می‌دهد — از روی آنچه در تایم‌لاین اندازه گرفته — و به‌جای حدس زدن، همین را می‌گوید.'
        )}
      </p>
    </Card>
  )
}

/**
 * The speech map — where the edit thinks someone is talking.
 *
 * Everything about the cut starts from this: which moments are candidates, where
 * a cut may land without breaking a word. For every release so far it came from
 * FFmpeg's energy detector, which cannot tell a loud tone from a voice. The model
 * is opt-in, and the reason it is opt-in is on this card: a **Measure** button
 * that runs both on a file the user chooses and shows the numbers, because
 * "the model is better" without a measurement is a brochure (§4.57).
 */
function SpeechEngineCard() {
  const { t } = useI18n()
  const [status, setStatus] = useState<VadStatus | null>(null)
  const [result, setResult] = useState<VadComparison | null>(null)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState('')

  useEffect(() => {
    vadApi.status().then(setStatus).catch(() => setStatus(null))
  }, [])

  const install = async () => {
    setBusy(true)
    setNote('')
    try {
      setStatus(await vadApi.install((state) => setNote(state.label || '')))
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setBusy(false)
      setNote('')
    }
  }

  const measure = async () => {
    const picker = pickMedia()
    const paths = picker ? await picker : null
    const path = paths?.[0]
    if (!path) return
    setBusy(true)
    try {
      setResult(await vadApi.compare(path))
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const choose = async (engine: string) => {
    try {
      await vadApi.choose(engine)
      setStatus(await vadApi.status())
      message.success(t('The speech map changed', 'نقشه‌ی گفتار عوض شد'))
    } catch (err) {
      message.error((err as Error).message)
    }
  }

  const percent = (value: number) => `${Math.round(value * 100)}%`

  return (
    <Card title={t('Where the speech is', 'گفتار کجاست')}>
      <p className="ce-hint">
        {t(
          'Every cut starts from knowing where someone is talking. The energy detector has been used until now; it cannot tell a loud tone from a voice. The model is 2.2 MB, licence MIT, and runs on the runtime that already ships.',
          'هر برشی از این شروع می‌شود که بدانیم کی دارد حرف می‌زند. تا حالا از تشخیص‌دهنده‌ی انرژی استفاده شده؛ آن نمی‌تواند یک تنِ بلند را از صداِ انسان تشخیص دهد. مدل ۲.۲ مگابایت است، مجوز MIT، و روی همان رانتایمی کار می‌کند که از قبل در برنامه هست.'
        )}
      </p>

      <div className="ce-badges" style={{ marginTop: 10 }}>
        <span className="ce-badge">
          {t('engine', 'موتور')}: <Num>{status?.engine ?? '—'}</Num>
        </span>
        <span className="ce-badge">
          {t('model', 'مدل')}: {status?.model ? `${status.modelMb} MB` : t('not fetched', 'گرفته نشده')}
        </span>
        <span className="ce-badge">
          onnxruntime: <Num>{status?.onnxruntime ?? '—'}</Num>
        </span>
        <span className="ce-badge">{t('licence', 'مجوز')}: {status?.licence ?? '—'}</span>
      </div>

      <div className="ce-actions" style={{ marginTop: 12 }}>
        {!status?.model && (
          <button className="ce-btn ce-btn--sm" disabled={busy} onClick={() => void install()}>
            <Download size={14} /> {busy && note ? note : t('Fetch the model (2.2 MB)', 'گرفتن مدل (۲.۲ مگابایت)')}
          </button>
        )}
        <button className="ce-btn ce-btn--ghost ce-btn--sm" disabled={busy} onClick={() => void measure()}>
          <Sparkles size={14} /> {t('Measure it on a file', 'اندازه‌گیری روی یک فایل')}
        </button>
        {status?.model && status.engine !== 'silero' && (
          <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void choose('silero')}>
            {t('Use the model', 'استفاده از مدل')}
          </button>
        )}
        {status?.engine === 'silero' && (
          <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void choose('energy')}>
            {t('Go back to the energy detector', 'برگشت به تشخیص‌دهنده‌ی انرژی')}
          </button>
        )}
      </div>

      {result && (
        <div className="ce-kv" style={{ marginTop: 12, flexDirection: 'column', alignItems: 'stretch' }}>
          <strong data-testid="vad-result">
            {result.file} · {result.duration.toFixed(1)}s
            {!result.hasAudio && t(' — no audio track', ' — ترک صوتی ندارد')}
          </strong>
          {result.silencedetect && (
            <span>
              {t('Energy detector', 'تشخیص‌دهنده‌ی انرژی')}: {percent(result.silencedetect.speechRatio)}{' '}
              {t('speech', 'گفتار')} · {result.silencedetect.regions} {t('regions', 'ناحیه')} ·{' '}
              {result.silencedetect.seconds}s
            </span>
          )}
          {result.silero && (
            <span>
              {t('Model', 'مدل')}: {percent(result.silero.speechRatio)} {t('speech', 'گفتار')} ·{' '}
              {result.silero.regions} {t('regions', 'ناحیه')} · {result.silero.seconds}s
            </span>
          )}
          {typeof result.disagreementRatio === 'number' && (
            <span className="ce-hint">
              {t('They disagree over', 'اختلاف دارند روی')} {percent(result.disagreementRatio)}{' '}
              {t('of the file. Only your own material can say which one is right.',
                 'از فایل. فقط material خودت می‌تواند بگوید کدام درست است.')}
            </span>
          )}
        </div>
      )}
    </Card>
  )
}

/**
 * On-screen text — what a frame says.
 *
 * Apache-2.0, models bundled in the wheel (15.4 MB) so it works with the network
 * unplugged, and it runs on the onnxruntime that already ships. It is the pass
 * behind copying the reference's caption style, seeing hand-made titles, and the
 * "no on-screen text" restriction. On-demand: nothing is in the installer.
 */
function OcrCard() {
  const { t } = useI18n()
  const [status, setStatus] = useState<OcrStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState('')

  useEffect(() => {
    ocrApi.status().then(setStatus).catch(() => setStatus(null))
  }, [])

  const install = async () => {
    setBusy(true)
    setNote('')
    try {
      await ocrApi.install((state) => setNote(state.label || ''))
      setStatus(await ocrApi.status())
      message.success(t('OCR is ready', 'OCR آماده است'))
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setBusy(false)
      setNote('')
    }
  }

  return (
    <Card title={t('On-screen text', 'متنِ روی تصویر')}>
      <p className="ce-hint">
        {t(
          'Reads what is written on the picture. It is the pass behind copying the reference caption style, seeing hand-made titles, and the "no on-screen text" restriction. Apache-2.0; the models travel inside the package, so once fetched it works offline.',
          'آنچه روی تصویر نوشته شده را می‌خواند: همان مرحله‌ای که کپی‌کردن سبک زیرنویسِ الگو، دیدن تایتل‌های دستی و محدودیت «متن روی تصویر نباشد» به آن وابسته‌اند. Apache-2.0؛ مدل‌ها داخل خود بسته‌اند، پس بعد از گرفتن، آفلاین کار می‌کند.'
        )}
      </p>
      <div className="ce-badges" style={{ marginTop: 10 }}>
        <span className="ce-badge">{t('licence', 'مجوز')}: {status?.licence ?? '—'}</span>
        <span className="ce-badge">
          {status?.installed ? t('installed', 'نصب است') : t('not fetched', 'گرفته نشده')}
        </span>
        <span className="ce-badge">
          {t('models', 'مدل‌ها')}: {status?.modelsBundled ? t('bundled', 'داخل بسته') : '—'}
        </span>
      </div>
      {!status?.installed && (
        <div className="ce-actions" style={{ marginTop: 12 }}>
          <button className="ce-btn ce-btn--sm" disabled={busy} onClick={() => void install()}>
            <Download size={14} /> {busy && note ? note : t('Fetch OCR (~16 MB)', 'گرفتن OCR (~۱۶ مگابایت)')}
          </button>
        </div>
      )}
    </Card>
  )
}

/**
 * A model that has seen frames.
 *
 * The models live in the user's own Ollama — a 4 GB laptop is never promised an
 * 11 B model, because the catalogue already says what fits (§4.62). The engine is
 * off by default: a boost that is absent is not a regression, and whether the
 * model's "interesting" agrees with a human is decided by the user's own footage
 * through the preview, not by a claim.
 */
function VisionCard() {
  const { t } = useI18n()
  const [status, setStatus] = useState<VisionStatus | null>(null)
  const [preview, setPreview] = useState<{ time: string; score: number }[] | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    visionApi.status().then(setStatus).catch(() => setStatus(null))
  }, [])

  const toggle = async (enabled: boolean) => {
    try {
      await visionApi.enable(enabled)
      setStatus(await visionApi.status())
    } catch (err) {
      message.error((err as Error).message)
    }
  }

  const measure = async () => {
    const picker = pickMedia()
    const paths = picker ? await picker : null
    if (!paths?.[0]) return
    setBusy(true)
    try {
      const r = await visionApi.preview(paths[0])
      setPreview(
        r.scores
          ? Object.entries(r.scores).map(([time, score]) => ({ time, score }))
          : null
      )
      if (!r.scores) message.warning(t('No vision model answered', 'مدل بینایی جوابی نداد'))
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title={t('A model that sees', 'مدلی که می‌بیند')}>
      <p className="ce-hint">
        {t(
          'One vote in the highlight scorer from a model that has actually looked at the frames. It runs in your own Ollama; nothing is installed. Off by default, and the preview shows the scores in the open.',
          'یک رأی در امتیازدهنده‌ی هایلایت از مدلی که واقعاً به فریم‌ها نگاه کرده. روی Ollama خودت اجرا می‌شود؛ هیچ‌چیز نصب نمی‌شود. به‌صورت پیش‌فرض خاموش، و پیش‌نمایش نمره‌ها را آشکار نشان می‌دهد.'
        )}
      </p>
      <div className="ce-badges" style={{ marginTop: 10 }}>
        <span className="ce-badge">
          {t('Ollama', 'الاما')}: {status?.running ? t('running', 'روشن') : t('not running', 'خاموش')}
        </span>
        <span className="ce-badge">
          {t('vision model', 'مدل بینایی')}: <Num>{status?.visionPulled ?? t('none pulled', 'گرفته نشده')}</Num>
        </span>
      </div>
      <div className="ce-actions" style={{ marginTop: 12 }}>
        <button
          className={`ce-btn ce-btn--sm ${status?.enabled ? 'ce-btn--auto' : ''}`}
          disabled={!status?.ready && !status?.enabled}
          onClick={() => void toggle(!status?.enabled)}
        >
          {status?.enabled ? t('Turn off', 'خاموش کن') : t('Let the model vote', 'بگذار مدل رأی بدهد')}
        </button>
        <button className="ce-btn ce-btn--ghost ce-btn--sm" disabled={busy || !status?.ready} onClick={() => void measure()}>
          <Sparkles size={14} /> {t('Preview on a file', 'پیش‌نمایش روی یک فایل')}
        </button>
      </div>
      {preview && (
        <div className="ce-kv" style={{ marginTop: 12, flexDirection: 'column', alignItems: 'stretch' }}>
          {preview.map((row) => (
            <span key={row.time} dir="ltr">
              {Number(row.time).toFixed(1)}s — {row.score.toFixed(2)}
            </span>
          ))}
        </div>
      )}
    </Card>
  )
}

declare const __APP_VERSION__: string
const APP_VERSION = __APP_VERSION__

const LANGUAGES: { value: Lang; label: string; native: string }[] = [
  { value: 'en', label: 'English', native: 'English' },
  { value: 'fa', label: 'Persian', native: 'فارسی' },
]

export default function Settings() {
  const { t, lang, setLang } = useI18n()
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)
  const [phase, setPhase] = useState<'idle' | 'checking' | 'downloading' | 'ready' | 'uptodate'>('idle')
  const [available, setAvailable] = useState<string | null>(null)
  const [progress, setProgress] = useState<UpdatePayload | null>(null)
  const [updateError, setUpdateError] = useState<string | null>(null)

  useEffect(() => {
    systemApi
      .settings()
      .then((data) => form.setFieldsValue({ ffmpeg_path: (data as Record<string, string>).ffmpeg_path || '' }))
      .catch(() => undefined)
  }, [form])

  // Subscribe to the Electron update bridge (no-op in the browser preview).
  useEffect(() => {
    const bridge = updateBridge()
    if (!bridge) return
    return bridge.onUpdateEvent((payload) => {
      switch (payload.type) {
        case 'checking':
          setPhase('checking'); setUpdateError(null); setProgress(null); break
        case 'available':
          setPhase('downloading'); setAvailable(payload.version ?? null); break
        case 'not-available':
          setPhase('uptodate'); break
        case 'progress':
          setPhase('downloading'); setProgress(payload); break
        case 'downloaded':
          setPhase('ready'); message.success('به‌روزرسانی آماده نصب است'); break
        case 'error':
          setPhase('idle'); setUpdateError(payload.error ?? 'خطای نامشخص'); break
      }
    })
  }, [])

  const bridge = updateBridge()

  return (
    <Page
      title={t('Settings', 'تنظیمات')}
      subtitle={t('Application configuration and updates', 'پیکربندی برنامه و به‌روزرسانی')}
      width="sm"
    >
      <Card title={t('Language', 'زبان')}>
        <p className="ce-hint" style={{ marginBottom: 12 }}>
          <Languages size={16} />
          {t(
            'Changes the whole interface immediately, including text direction. Your choice is remembered.',
            'کل رابط کاربری بلافاصله تغییر می‌کند، شامل جهت متن. انتخاب تو ذخیره می‌شود.'
          )}
        </p>
        <div className="ce-langgrid">
          {LANGUAGES.map((option) => (
            <button
              key={option.value}
              className={`ce-langbtn ${lang === option.value ? 'is-active' : ''}`}
              onClick={() => setLang(option.value)}
            >
              <span className="ce-langbtn__native">{option.native}</span>
              <span className="ce-langbtn__label">{option.label}</span>
              {lang === option.value && <CheckCircle2 size={16} className="ce-langbtn__check" />}
            </button>
          ))}
        </div>
      </Card>

      <Card title={t('Local AI engines', 'موتورهای هوش مصنوعی محلی')}>
        <AiRuntimeCard />
        <GpuCard />
      </Card>

      <Card title={t('Application update', 'به‌روزرسانی برنامه')}>
        <div className="ce-badges">
          <span className="ce-badge">
            {t('Current version', 'نسخه فعلی')} <Num>{APP_VERSION}</Num>
          </span>
          {phase === 'checking' && <span className="ce-badge ce-badge--muted">{t('Checking…', 'در حال بررسی…')}</span>}
          {phase === 'uptodate' && <span className="ce-badge ce-badge--ok">{t('You are up to date', 'به‌روز هستی')}</span>}
          {available && phase !== 'ready' && (
            <span className="ce-badge ce-badge--warn">
              {t('New version', 'نسخه جدید')}: <Num>{available}</Num>
            </span>
          )}
          {phase === 'ready' && (
            <span className="ce-badge ce-badge--ok">
              <CheckCircle2 size={13} /> {t('Ready to install', 'آماده نصب')}
            </span>
          )}
        </div>

        {phase === 'downloading' && (
          <div className="ce-update">
            <span className="ce-progress">
              <span
                className="ce-progress__bar"
                style={{
                  width: `${Math.max(2, progress?.percent ?? 0)}%`,
                  background: 'linear-gradient(90deg,#6366F1,#8B5CF6)',
                }}
              />
            </span>
            <div className="ce-update__row">
              <span>
                <Num>{formatBytes(progress?.transferred)}</Num> {t('of', 'از')}{' '}
                <Num>{formatBytes(progress?.total)}</Num>
              </span>
              <span>
                <Num>{formatBytes(progress?.bytesPerSecond)}</Num>/s
              </span>
            </div>
            <p className="ce-hint">
              {t(
                'Only the changed blocks are downloaded — if the number above is far below the full installer size, the differential patch is working.',
                'فقط بخش‌های تغییرکرده دانلود می‌شود؛ اگر عدد بالا خیلی کمتر از حجم کامل نصب‌کننده است، یعنی پچ تفاضلی فعال شده.'
              )}
            </p>
          </div>
        )}

        <div className="ce-actions" style={{ marginTop: 14 }}>
          {phase !== 'ready' ? (
            <button
              className="ce-btn ce-btn--sm"
              disabled={phase === 'checking' || phase === 'downloading'}
              onClick={() => {
                if (!bridge) {
                  message.info(
                    t('Auto-update only works in the installed Windows app', 'به‌روزرسانی خودکار فقط در نسخه‌ی نصب‌شده ویندوز کار می‌کند')
                  )
                  return
                }
                setPhase('checking')
                bridge.runUpdate()
              }}
            >
              {phase === 'downloading' ? (
                <><RefreshCw size={15} className="ce-spin" /> {t('Downloading…', 'در حال دریافت…')}</>
              ) : phase === 'checking' ? (
                <><RefreshCw size={15} className="ce-spin" /> {t('Checking…', 'بررسی…')}</>
              ) : (
                <><Sparkles size={15} /> {t('Check and install update', 'بررسی و نصب به‌روزرسانی')}</>
              )}
            </button>
          ) : (
            <button className="ce-btn ce-btn--sm" onClick={() => bridge?.installUpdate()}>
              <Download size={15} /> {t('Install and restart', 'نصب و راه‌اندازی مجدد')}
            </button>
          )}
        </div>

        {updateError && <p className="ce-error">{updateError}</p>}
        <p className="ce-hint">
          {t(
            'One button does everything: check, differential download and install. The app also checks silently at startup.',
            'یک دکمه کل کار را انجام می‌دهد: بررسی، دانلود تفاضلی و نصب. برنامه هنگام اجرا هم به‌صورت خودکار و بی‌صدا نسخه‌ی جدید را بررسی می‌کند.'
          )}
        </p>
      </Card>

      <Card title={t('General', 'عمومی')}>
        <Form
          form={form}
          layout="vertical"
          onFinish={async (values) => {
            setSaving(true)
            try {
              await systemApi.updateSettings({ ffmpeg_path: values.ffmpeg_path })
              message.success(t('Settings saved', 'تنظیمات ذخیره شد'))
            } catch {
              message.error(t('Could not save settings', 'ذخیره تنظیمات ناموفق بود'))
            } finally {
              setSaving(false)
            }
          }}
        >
          <Form.Item name="ffmpeg_path" label={t('FFmpeg path', 'مسیر FFmpeg')}>
            <Input dir="ltr" placeholder={t('leave empty to auto-detect', 'خالی بگذار تا خودکار پیدا شود')} />
          </Form.Item>
          <button className="ce-btn ce-btn--sm" type="submit" disabled={saving}>
            {saving ? t('Saving…', 'در حال ذخیره…') : t('Save settings', 'ذخیره تنظیمات')}
          </button>
        </Form>
      </Card>

      <Card title={t('AI engines', 'موتورهای هوش مصنوعی')}>
        <p className="ce-hint">
          {t('Gemini, Claude, OpenAI and Ollama keys are read from ', 'کلیدهای Gemini، Claude، OpenAI و Ollama از فایل ')}
          <Num>config.json</Num>
          {t(' in ', ' در پوشه‌ی ')}
          <Num>~/CuttingEdge</Num>
          {t('. Ollama runs fully locally and needs no key.', ' خوانده می‌شوند. Ollama کاملاً محلی و بدون نیاز به کلید کار می‌کند.')}
        </p>
      </Card>

      <AssistantEngineCard />

      <SpeechEngineCard />

      <OcrCard />

      <VisionCard />
    </Page>
  )
}

import { useEffect, useState } from 'react'
import { message } from 'antd'
import {
  Brain, Mic, CheckCircle2, XCircle, RefreshCw, Download, ExternalLink, Loader2, Timer,
} from 'lucide-react'
import { aiApi, type EngineState, type EngineTest } from '../api/ai'
import { useI18n } from '../i18n'

/**
 * The local AI, checked rather than assumed.
 *
 * Both engines are optional and both are easy to have *almost* working: Ollama
 * installed but not running, a model never pulled, a first transcription that
 * takes a minute. A green tick that means "the import succeeded" would be
 * useless, so this card reports what is installed, what answers, and **how many
 * seconds** it took on this machine.
 */
export default function AiRuntimeCard() {
  const { t } = useI18n()
  const [state, setState] = useState<{ ollama: EngineState; whisper: EngineState } | null>(null)
  const [result, setResult] = useState<{ ollama: EngineTest; whisper: EngineTest } | null>(null)
  const [busy, setBusy] = useState<'status' | 'test' | 'ollama' | 'whisper' | null>(null)

  const refresh = async () => {
    setBusy('status')
    try {
      setState(await aiApi.status())
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setBusy(null)
    }
  }

  useEffect(() => {
    void refresh()
  }, [])

  const runTest = async () => {
    setBusy('test')
    try {
      setResult(await aiApi.test())
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setBusy(null)
    }
  }

  const line = (engine: EngineState, test: EngineTest | undefined, icon: React.ReactNode) => (
    <div className="ce-engine">
      <span className="ce-engine__icon">{icon}</span>
      <div className="ce-engine__body">
        <div className="ce-engine__head">
          <strong>{engine.name}</strong>
          {engine.running ? (
            <span className="ce-badge ce-badge--ok"><CheckCircle2 size={12} /> {t('ready', 'آماده')}</span>
          ) : engine.installed ? (
            <span className="ce-badge ce-badge--warn">{t('installed, not running', 'نصب است، اجرا نیست')}</span>
          ) : (
            <span className="ce-badge ce-badge--muted"><XCircle size={12} /> {t('not installed', 'نصب نیست')}</span>
          )}
          {engine.models.length > 0 && (
            <span className="ce-badge" dir="ltr">{engine.models.slice(0, 3).join(', ')}</span>
          )}
        </div>

        {test && (
          <p className="ce-hint">
            {test.ok ? (
              <>
                <Timer size={13} /> {t('answered in', 'پاسخ در')} <strong dir="ltr">{test.seconds}s</strong>
                {test.model ? ` · ${test.model}` : ''}
                {typeof test.cues === 'number' ? ` · ${test.cues} ${t('cues', 'قطعه')}` : ''}
              </>
            ) : (
              <>
                <XCircle size={13} /> {test.detail}
              </>
            )}
          </p>
        )}
      </div>
    </div>
  )

  return (
    <>
      <p className="ce-hint" style={{ marginBottom: 12 }}>
        {t(
          'Both engines are optional and run on this machine — nothing is uploaded. The editor works without them; they only make the assistant and the captions better.',
          'هر دو موتور اختیاری‌اند و روی همین کامپیوتر اجرا می‌شوند — چیزی جایی آپلود نمی‌شود. برنامه بدون آن‌ها هم کار می‌کند؛ فقط دستیار و زیرنویس را بهتر می‌کنند.'
        )}
      </p>

      {state && (
        <div className="ce-engines">
          {line(state.ollama, result?.ollama, <Brain size={18} />)}
          {line(state.whisper, result?.whisper, <Mic size={18} />)}
        </div>
      )}

      <div className="ce-actions" style={{ marginTop: 14, flexWrap: 'wrap' }}>
        <button className="ce-btn ce-btn--sm" disabled={busy !== null} onClick={() => void runTest()}>
          {busy === 'test' ? <Loader2 size={15} className="ce-spin" /> : <RefreshCw size={15} />}
          {t('Check and time them', 'بررسی و زمان‌سنجی')}
        </button>

        {state?.ollama.running && (
          <button
            className="ce-btn ce-btn--ghost ce-btn--sm"
            disabled={busy !== null}
            onClick={async () => {
              setBusy('ollama')
              try {
                const done = await aiApi.pullModel(state.ollama.selected || 'llama3')
                message.success(t(`Model ready in ${done.seconds}s`, `مدل در ${done.seconds} ثانیه آماده شد`))
                void refresh()
              } catch (error) {
                message.error((error as Error).message)
              } finally {
                setBusy(null)
              }
            }}
          >
            {busy === 'ollama' ? <Loader2 size={15} className="ce-spin" /> : <Download size={15} />}
            {t('Download the model', 'دانلود مدل')}
          </button>
        )}

        {!state?.ollama.installed && state?.ollama.download && (
          <a className="ce-btn ce-btn--ghost ce-btn--sm" href={state.ollama.download} target="_blank" rel="noreferrer">
            <ExternalLink size={15} /> {t('Get Ollama', 'گرفتن Ollama')}
          </a>
        )}

        {state?.whisper.installed && (
          <button
            className="ce-btn ce-btn--ghost ce-btn--sm"
            disabled={busy !== null}
            onClick={async () => {
              setBusy('whisper')
              try {
                const done = await aiApi.downloadWhisper('base')
                message.success(t(`Speech model ready in ${done.seconds}s`, `مدل گفتار در ${done.seconds} ثانیه آماده شد`))
                void refresh()
              } catch (error) {
                message.error((error as Error).message)
              } finally {
                setBusy(null)
              }
            }}
          >
            {busy === 'whisper' ? <Loader2 size={15} className="ce-spin" /> : <Download size={15} />}
            {t('Fetch the speech model', 'دریافت مدل گفتار')}
          </button>
        )}
      </div>

      <p className="ce-hint" style={{ marginTop: 10 }}>
        {t(
          'Ollama itself is a separate application of a few hundred megabytes; the app will not install it silently. Pulling a model into an Ollama you already run is one click above.',
          'خود Ollama یک برنامه‌ی جداگانه‌ی چندصد مگابایتی است و برنامه بی‌اجازه نصبش نمی‌کند. دانلود مدل داخل Ollama‌ای که از قبل داری، همان دکمه‌ی بالاست.'
        )}
      </p>
    </>
  )
}

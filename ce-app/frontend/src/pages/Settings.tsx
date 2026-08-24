import { useEffect, useState } from 'react'
import { Form, Input, message } from 'antd'
import { RefreshCw, Download, CheckCircle2, Sparkles, Languages } from 'lucide-react'
import Page, { Card, Num } from '../components/Page'
import { systemApi } from '../api/jobs'
import AiRuntimeCard from '../components/AiRuntimeCard'
import GpuCard from '../components/GpuCard'
import { formatBytes, updateBridge, type UpdatePayload } from '../services/updater'
import { useI18n, type Lang } from '../i18n'

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
    </Page>
  )
}

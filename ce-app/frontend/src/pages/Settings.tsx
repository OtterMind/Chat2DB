import { useEffect, useState } from 'react'
import { Form, Input, message } from 'antd'
import { RefreshCw, Download, CheckCircle2, Sparkles } from 'lucide-react'
import Page, { Card, Num } from '../components/Page'
import { systemApi } from '../api/jobs'
import { formatBytes, updateBridge, type UpdatePayload } from '../services/updater'

declare const __APP_VERSION__: string
const APP_VERSION = __APP_VERSION__

export default function Settings() {
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
    <Page title="تنظیمات" subtitle="پیکربندی برنامه و به‌روزرسانی" width="sm">
      <Card title="به‌روزرسانی برنامه">
        <div className="ce-badges">
          <span className="ce-badge">نسخه فعلی <Num>{APP_VERSION}</Num></span>
          {phase === 'checking' && <span className="ce-badge ce-badge--muted">در حال بررسی…</span>}
          {phase === 'uptodate' && <span className="ce-badge ce-badge--ok">به‌روز هستی</span>}
          {available && phase !== 'ready' && (
            <span className="ce-badge ce-badge--warn">نسخه جدید: <Num>{available}</Num></span>
          )}
          {phase === 'ready' && (
            <span className="ce-badge ce-badge--ok">
              <CheckCircle2 size={13} /> آماده نصب
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
                <Num>{formatBytes(progress?.transferred)}</Num> از <Num>{formatBytes(progress?.total)}</Num>
              </span>
              <span>
                <Num>{formatBytes(progress?.bytesPerSecond)}</Num>/s
              </span>
            </div>
            <p className="ce-hint">
              فقط بخش‌های تغییرکرده دانلود می‌شود؛ اگر عدد بالا خیلی کمتر از حجم کامل نصب‌کننده است،
              یعنی پچ تفاضلی فعال شده.
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
                  message.info('به‌روزرسانی خودکار فقط در نسخه‌ی نصب‌شده ویندوز کار می‌کند')
                  return
                }
                setPhase('checking')
                bridge.runUpdate()
              }}
            >
              {phase === 'downloading' ? (
                <><RefreshCw size={15} className="ce-spin" /> در حال دریافت…</>
              ) : phase === 'checking' ? (
                <><RefreshCw size={15} className="ce-spin" /> بررسی…</>
              ) : (
                <><Sparkles size={15} /> بررسی و نصب به‌روزرسانی</>
              )}
            </button>
          ) : (
            <button className="ce-btn ce-btn--sm" onClick={() => bridge?.installUpdate()}>
              <Download size={15} /> نصب و راه‌اندازی مجدد
            </button>
          )}
        </div>

        {updateError && <p className="ce-error">{updateError}</p>}
        <p className="ce-hint">
          یک دکمه کل کار را انجام می‌دهد: بررسی، دانلود تفاضلی و نصب. برنامه هنگام اجرا هم
          به‌صورت خودکار و بی‌صدا نسخه‌ی جدید را بررسی می‌کند.
        </p>
      </Card>

      <Card title="عمومی">
        <Form
          form={form}
          layout="vertical"
          onFinish={async (values) => {
            setSaving(true)
            try {
              await systemApi.updateSettings({ ffmpeg_path: values.ffmpeg_path })
              message.success('تنظیمات ذخیره شد')
            } catch {
              message.error('ذخیره تنظیمات ناموفق بود')
            } finally {
              setSaving(false)
            }
          }}
        >
          <Form.Item name="ffmpeg_path" label="مسیر FFmpeg">
            <Input dir="ltr" placeholder="خالی بگذار تا خودکار پیدا شود" />
          </Form.Item>
          <button className="ce-btn ce-btn--sm" type="submit" disabled={saving}>
            {saving ? 'در حال ذخیره…' : 'ذخیره تنظیمات'}
          </button>
        </Form>
      </Card>

      <Card title="موتورهای هوش مصنوعی">
        <p className="ce-hint">
          کلیدهای Gemini، Claude، OpenAI و Ollama از فایل <Num>config.json</Num> در پوشه‌ی
          <Num> ~/CuttingEdge</Num> خوانده می‌شوند. Ollama کاملاً محلی و بدون نیاز به کلید کار می‌کند.
        </p>
      </Card>
    </Page>
  )
}

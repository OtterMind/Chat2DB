import { useEffect, useState } from 'react'
import { Form, Input, message } from 'antd'
import { RefreshCw, Download, CheckCircle2 } from 'lucide-react'
import Page, { Card, Num } from '../components/Page'
import { systemApi } from '../api/jobs'

declare const __APP_VERSION__: string
const APP_VERSION = __APP_VERSION__

export default function Settings() {
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)
  const [checking, setChecking] = useState(false)
  const [available, setAvailable] = useState<string | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [downloaded, setDownloaded] = useState(false)
  const [updateError, setUpdateError] = useState<string | null>(null)

  useEffect(() => {
    systemApi
      .settings()
      .then((data) => form.setFieldsValue({ ffmpeg_path: (data as Record<string, string>).ffmpeg_path || '' }))
      .catch(() => undefined)

    const onMessage = (event: MessageEvent) => {
      const msg = event.data
      if (!msg?.type) return
      switch (msg.type) {
        case 'update:checking': setChecking(true); setAvailable(null); setProgress(null); setUpdateError(null); break
        case 'update:available': setChecking(false); setAvailable(msg.version ?? 'نسخه جدید'); break
        case 'update:progress': setProgress(msg.percent ?? 0); break
        case 'update:downloaded': setDownloaded(true); message.success('به‌روزرسانی دانلود شد — آماده نصب'); break
        case 'update:error': setChecking(false); setUpdateError(msg.error ?? 'خطای به‌روزرسانی'); break
      }
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [form])

  const bridge = (window as unknown as { cuttingEdge?: Record<string, () => void> }).cuttingEdge

  return (
    <Page title="تنظیمات" subtitle="پیکربندی برنامه و به‌روزرسانی" width="sm">
      <Card title="به‌روزرسانی برنامه">
        <div className="ce-badges">
          <span className="ce-badge">نسخه فعلی <Num>{APP_VERSION}</Num></span>
          {checking && <span className="ce-badge ce-badge--muted">در حال بررسی…</span>}
          {available && !downloaded && <span className="ce-badge ce-badge--warn">نسخه جدید: <Num>{available}</Num></span>}
          {downloaded && (
            <span className="ce-badge ce-badge--ok">
              <CheckCircle2 size={13} /> آماده نصب
            </span>
          )}
        </div>

        {progress !== null && (
          <span className="ce-progress" style={{ marginTop: 12 }}>
            <span className="ce-progress__bar" style={{ width: `${progress}%`, background: 'linear-gradient(90deg,#6366F1,#8B5CF6)' }} />
          </span>
        )}

        <div className="ce-actions" style={{ marginTop: 14 }}>
          <button
            className="ce-btn ce-btn--ghost ce-btn--sm"
            disabled={checking}
            onClick={() => {
              if (bridge?.checkUpdate) bridge.checkUpdate()
              else message.info('به‌روزرسانی خودکار فقط در نسخه‌ی نصب‌شده ویندوز کار می‌کند')
            }}
          >
            <RefreshCw size={15} className={checking ? 'ce-spin' : ''} /> بررسی به‌روزرسانی
          </button>
          {downloaded && (
            <button className="ce-btn ce-btn--sm" onClick={() => bridge?.installUpdate?.()}>
              <Download size={15} /> نصب و راه‌اندازی مجدد
            </button>
          )}
        </div>

        {updateError && <p className="ce-error">{updateError}</p>}
        <p className="ce-hint">به‌روزرسانی بدون حذف و نصب مجدد انجام می‌شود و فقط تفاوت فایل‌ها دانلود می‌شود.</p>
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

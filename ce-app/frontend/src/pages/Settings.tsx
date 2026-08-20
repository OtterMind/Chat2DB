import { useEffect, useState } from 'react'
import { Card, Form, Input, Button, Typography, message, Divider, Tag, Space, Progress } from 'antd'
import { ReloadOutlined, DownloadOutlined, CheckCircleOutlined } from '@ant-design/icons'
import { systemApi } from '../api/jobs'

function Settings() {
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)

  // --- Auto-update state ---
  const [checking, setChecking] = useState(false)
  const [available, setAvailable] = useState<string | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [downloaded, setDownloaded] = useState(false)
  const [updateError, setUpdateError] = useState<string | null>(null)

  useEffect(() => {
    const load = async () => {
      const data = await systemApi.settings() as Record<string, string>
      form.setFieldsValue({ ffmpeg_path: data.ffmpeg_path || '' })
    }
    load()
    // Listen for auto-update events from Electron main
    window.addEventListener('message', (event) => {
      const msg = event.data
      if (!msg?.type) return
      switch (msg.type) {
        case 'update:checking': setChecking(true); setAvailable(null); setProgress(null); setUpdateError(null); break
        case 'update:available': setChecking(false); setAvailable(msg.version ?? 'new version'); break
        case 'update:progress': setProgress(msg.percent ?? 0); break
        case 'update:downloaded': setDownloaded(true); message.success('Update downloaded — ready to install!'); break
        case 'update:error': setChecking(false); setUpdateError(msg.error ?? 'Update error'); message.error('Update error: ' + (msg.error ?? '')); break
      }
    })
  }, [form])

  const handleSave = async (values: Record<string, unknown>) => {
    setSaving(true)
    try {
      await systemApi.updateSettings({ ffmpeg_path: values.ffmpeg_path })
      message.success('Settings saved')
    } catch { message.error('Failed to save settings') } finally { setSaving(false) }
  }

  const checkUpdate = () => {
    setChecking(true); setUpdateError(null)
    const ce = (window as any).cuttingEdge
    if (ce?.checkUpdate) ce.checkUpdate()
    else message.info('Auto-update works in the installed Windows app (this preview runs in browser mode)')
    setChecking(false)
  }

  const installUpdate = () => {
    const ce = (window as any).cuttingEdge
    if (ce?.installUpdate) ce.installUpdate()
  }

  return (
    <div style={{ maxWidth: 700, margin: '0 auto' }}>
      <Typography.Title level={3}>Settings</Typography.Title>

      {/* Auto-Update card */}
      <Card title="بررسی و نصب به‌روزرسانی (Auto-Update)" style={{ marginBottom: 24 }}>
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <div>
            <Tag color="blue">نسخه فعلی: 0.2.0</Tag>
            {checking && <Tag>در حال بررسی...</Tag>}
            {downloaded && <Tag color="green" icon={<CheckCircleOutlined />}>آماده نصب</Tag>}
            {available && !downloaded && <Tag color="gold">نسخه جدید: {available}</Tag>}
          </div>
          {progress !== null && <Progress percent={Math.round(progress)} status="active" />}
          <Space>
            <Button icon={<ReloadOutlined />} loading={checking} onClick={checkUpdate}>بررسی آپدیت</Button>
            {downloaded && (
              <Button type="primary" icon={<DownloadOutlined />} onClick={installUpdate}>نصب و راه‌اندازی مجدد</Button>
            )}
          </Space>
          {updateError && <Typography.Text type="danger">{updateError}</Typography.Text>}
          <Typography.Text type="secondary">
            این قابلیت در نسخه نصب‌شده ویندوز کار می‌کند — تغییرات را بدون حذف و نصب مجدد اعمال می‌کند و فقط فایل‌های کوچک دانلود می‌شوند.
          </Typography.Text>
        </Space>
      </Card>

      <Card title="General" style={{ marginBottom: 24 }}>
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="ffmpeg_path" label="FFmpeg Path">
            <Input placeholder="Auto-detect (leave empty)" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={saving}>Save Settings</Button>
        </Form>
      </Card>

      <Card title="AI Providers">
        <Typography.Text type="secondary">
          Provider keys are configured via .env / config.json. Default: Google Gemini.
        </Typography.Text>
        <Divider />
        <Typography.Text>Gemini, Claude, OpenAI, Ollama (local) are supported.</Typography.Text>
      </Card>
    </div>
  )
}

export default Settings
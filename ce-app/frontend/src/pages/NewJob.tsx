import { useState } from 'react'
import { Form, Input, Select, InputNumber, Switch, message } from 'antd'
import { useNavigate, useSearchParams } from 'react-router-dom'
import Page, { Card } from '../components/Page'
import { jobsApi } from '../api/jobs'
import { useI18n } from '../i18n'

const PRESET_LABEL: Record<string, [en: string, fa: string]> = {
  autoclip: ['Auto Clip', 'کلیپ خودکار'],
  reframe: ['Reframe', 'قاب عمودی'],
  facetrack: ['Face Tracking', 'فیس‌ترکینگ'],
  subtitles: ['Smart Captions', 'زیرنویس هوشمند'],
}

export default function NewJob() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const preset = params.get('preset') ?? 'autoclip'
  const { t, lang } = useI18n()
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm()

  const handleSubmit = async (values: Record<string, unknown>) => {
    setSubmitting(true)
    try {
      const job = await jobsApi.create({
        name: String(values.name ?? ''),
        source_url: String(values.source_url ?? ''),
        source_type: String(values.source_type ?? 'youtube'),
        config: {
          preset,
          clips_count: values.clips_count ?? 5,
          ratio: values.ratio ?? '9:16',
          hook_enabled: values.hook_enabled ?? true,
          captions_enabled: values.captions_enabled ?? true,
          bgm_enabled: values.bgm_enabled ?? true,
          face_detector: 'mediapipe',
          diarization_enabled: values.diarization_enabled ?? false,
          ai_provider: values.ai_provider ?? 'gemini',
        },
      })
      message.success(t('Project created', 'پروژه ساخته شد'))
      navigate(`/jobs/${job.id}`)
    } catch (err) {
      message.error(t('Could not create the project: ', 'ساخت پروژه ناموفق بود: ') + (err as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Page
      title={t('New project', 'پروژه جدید')}
      subtitle={`${t('Mode', 'حالت')}: ${(PRESET_LABEL[preset] ?? PRESET_LABEL.autoclip)[lang === 'fa' ? 1 : 0]}`}
      back
      width="sm"
    >
      <Form
        form={form}
        layout="vertical"
        onFinish={handleSubmit}
        initialValues={{
          source_type: 'youtube',
          clips_count: 5,
          ratio: '9:16',
          hook_enabled: true,
          captions_enabled: true,
          bgm_enabled: true,
          diarization_enabled: false,
          ai_provider: 'gemini',
        }}
      >
        <Card title={t('Video source', 'منبع ویدیو')}>
          <Form.Item
            name="name"
            label={t('Project name', 'نام پروژه')}
            rules={[{ required: true, message: t('Enter a name', 'یک نام وارد کن') }]}
          >
            <Input placeholder={t('e.g. Weekly podcast — episode 12', 'مثلاً: پادکست هفتگی — قسمت ۱۲')} />
          </Form.Item>
          <Form.Item name="source_type" label={t('Source type', 'نوع منبع')}>
            <Select
              options={[
                { value: 'youtube', label: t('YouTube', 'یوتیوب') },
                { value: 'instagram', label: t('Instagram', 'اینستاگرام') },
                { value: 'tiktok', label: t('TikTok', 'تیک‌تاک') },
                { value: 'local', label: t('Local file', 'فایل روی سیستم') },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="source_url"
            label={t('Link or file path', 'لینک یا مسیر فایل')}
            rules={[{ required: true, message: t('Enter a link or a file path', 'لینک یا مسیر فایل را وارد کن') }]}
          >
            <Input dir="ltr" placeholder="https://youtube.com/watch?v=…" />
          </Form.Item>
        </Card>

        <Card title={t('Output', 'خروجی')}>
          <div className="ce-formgrid">
            <Form.Item name="clips_count" label={t('Number of clips', 'تعداد کلیپ')}>
              <InputNumber min={1} max={50} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="ratio" label={t('Aspect ratio', 'نسبت تصویر')}>
              <Select
                options={[
                  { value: '9:16', label: t('9:16 — Shorts & Reels', '۹:۱۶ — شورتس و ریلز') },
                  { value: '1:1', label: t('1:1 — Square', '۱:۱ — مربع') },
                  { value: '4:5', label: t('4:5 — Portrait', '۴:۵ — پرتره') },
                  { value: '16:9', label: t('16:9 — Landscape', '۱۶:۹ — افقی') },
                ]}
              />
            </Form.Item>
            <Form.Item name="ai_provider" label={t('AI engine', 'موتور هوش مصنوعی')}>
              <Select
                options={[
                  { value: 'gemini', label: 'Google Gemini' },
                  { value: 'anthropic', label: 'Anthropic Claude' },
                  { value: 'openai', label: 'OpenAI' },
                  { value: 'ollama', label: t('Ollama — fully local', 'Ollama — کاملاً محلی') },
                ]}
              />
            </Form.Item>
          </div>
        </Card>

        <Card title={t('Features', 'امکانات')}>
          <div className="ce-switchlist">
            <Form.Item name="hook_enabled" label={t('Cinematic hook at the start', 'هوک سینمایی در ابتدای کلیپ')} valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="captions_enabled" label={t('Automatic captions', 'زیرنویس خودکار')} valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="bgm_enabled" label={t('Background music', 'موسیقی پس‌زمینه')} valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="diarization_enabled" label={t('Speaker detection (podcasts)', 'تشخیص گوینده (مناسب پادکست)')} valuePropName="checked">
              <Switch />
            </Form.Item>
          </div>
        </Card>

        <button className="ce-btn ce-btn--block" type="submit" disabled={submitting}>
          {submitting ? t('Creating…', 'در حال ساخت…') : t('Create and start processing', 'ساخت و شروع پردازش')}
        </button>
      </Form>
    </Page>
  )
}

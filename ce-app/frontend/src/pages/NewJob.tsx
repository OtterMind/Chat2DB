import { useState } from 'react'
import { Form, Input, Select, InputNumber, Switch, message } from 'antd'
import { useNavigate, useSearchParams } from 'react-router-dom'
import Page, { Card } from '../components/Page'
import { jobsApi } from '../api/jobs'

const PRESET_LABEL: Record<string, string> = {
  autoclip: 'کلیپ خودکار',
  reframe: 'قاب عمودی',
  facetrack: 'فیس‌ترکینگ',
  subtitles: 'زیرنویس هوشمند',
}

export default function NewJob() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const preset = params.get('preset') ?? 'autoclip'
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
      message.success('پروژه ساخته شد')
      navigate(`/jobs/${job.id}`)
    } catch (err) {
      message.error('ساخت پروژه ناموفق بود: ' + (err as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Page
      title="پروژه جدید"
      subtitle={`حالت: ${PRESET_LABEL[preset] ?? 'کلیپ خودکار'}`}
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
        <Card title="منبع ویدیو">
          <Form.Item name="name" label="نام پروژه" rules={[{ required: true, message: 'یک نام وارد کن' }]}>
            <Input placeholder="مثلاً: پادکست هفتگی — قسمت ۱۲" />
          </Form.Item>
          <Form.Item name="source_type" label="نوع منبع">
            <Select
              options={[
                { value: 'youtube', label: 'یوتیوب' },
                { value: 'instagram', label: 'اینستاگرام' },
                { value: 'tiktok', label: 'تیک‌تاک' },
                { value: 'local', label: 'فایل روی سیستم' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="source_url"
            label="لینک یا مسیر فایل"
            rules={[{ required: true, message: 'لینک یا مسیر فایل را وارد کن' }]}
          >
            <Input dir="ltr" placeholder="https://youtube.com/watch?v=…" />
          </Form.Item>
        </Card>

        <Card title="خروجی">
          <div className="ce-formgrid">
            <Form.Item name="clips_count" label="تعداد کلیپ">
              <InputNumber min={1} max={50} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="ratio" label="نسبت تصویر">
              <Select
                options={[
                  { value: '9:16', label: '۹:۱۶ — شورتس و ریلز' },
                  { value: '1:1', label: '۱:۱ — مربع' },
                  { value: '4:5', label: '۴:۵ — پرتره' },
                  { value: '16:9', label: '۱۶:۹ — افقی' },
                ]}
              />
            </Form.Item>
            <Form.Item name="ai_provider" label="موتور هوش مصنوعی">
              <Select
                options={[
                  { value: 'gemini', label: 'Google Gemini' },
                  { value: 'anthropic', label: 'Anthropic Claude' },
                  { value: 'openai', label: 'OpenAI' },
                  { value: 'ollama', label: 'Ollama — کاملاً محلی' },
                ]}
              />
            </Form.Item>
          </div>
        </Card>

        <Card title="امکانات">
          <div className="ce-switchlist">
            <Form.Item name="hook_enabled" label="هوک سینمایی در ابتدای کلیپ" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="captions_enabled" label="زیرنویس خودکار" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="bgm_enabled" label="موسیقی پس‌زمینه" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="diarization_enabled" label="تشخیص گوینده (مناسب پادکست)" valuePropName="checked">
              <Switch />
            </Form.Item>
          </div>
        </Card>

        <button className="ce-btn ce-btn--block" type="submit" disabled={submitting}>
          {submitting ? 'در حال ساخت…' : 'ساخت و شروع پردازش'}
        </button>
      </Form>
    </Page>
  )
}

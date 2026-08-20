import { useState } from 'react'
import { Card, Form, Input, Select, InputNumber, Switch, Button, Space, Typography, message } from 'antd'
import { useNavigate } from 'react-router-dom'
import { jobsApi } from '../api/jobs'

function NewJob() {
  const navigate = useNavigate()
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm()

  const handleSubmit = async (values: Record<string, unknown>) => {
    setSubmitting(true)
    try {
      const config = {
        clips_count: values.clips_count ?? 5,
        ratio: values.ratio ?? '9:16',
        hook_enabled: values.hook_enabled ?? true,
        captions_enabled: values.captions_enabled ?? true,
        caption_style: values.caption_style ?? 'default',
        bgm_enabled: values.bgm_enabled ?? true,
        face_detector: values.face_detector ?? 'mediapipe',
        diarization_enabled: values.diarization_enabled ?? false,
        ai_provider: values.ai_provider ?? 'gemini',
      }
      const job = await jobsApi.create({
        name: String(values.name ?? ''),
        source_url: String(values.source_url ?? ''),
        source_type: String(values.source_type ?? 'youtube'),
        config,
      })
      message.success('Job created!')
      navigate(`/jobs/${job.id}`)
    } catch (err) {
      message.error('Failed to create job: ' + (err as Error).message)
    } finally { setSubmitting(false) }
  }

  return (
    <div style={{ maxWidth: 800, margin: '0 auto' }}>
      <Typography.Title level={3} style={{ marginBottom: 24 }}>Create New Job</Typography.Title>
      <Card>
        <Form form={form} layout="vertical" onFinish={handleSubmit} initialValues={{
          source_type: 'youtube', clips_count: 5, ratio: '9:16', hook_enabled: true,
          captions_enabled: true, caption_style: 'default', bgm_enabled: true,
          face_detector: 'mediapipe', diarization_enabled: false, ai_provider: 'gemini',
        }}>
          <Form.Item name="name" label="Job Name" rules={[{ required: true, message: 'Please enter a job name' }]}>
            <Input placeholder="My first clipping job" />
          </Form.Item>
          <Form.Item name="source_type" label="Source Type">
            <Select options={[
              { value: 'youtube', label: 'YouTube' },
              { value: 'tiktok', label: 'TikTok' },
              { value: 'instagram', label: 'Instagram' },
              { value: 'local', label: 'Local File' },
            ]} />
          </Form.Item>
          <Form.Item name="source_url" label="Source URL / File Path" rules={[{ required: true, message: 'Please enter a URL or path' }]}>
            <Input placeholder="https://youtube.com/watch?v=...  یا مسیر فایل محلی" />
          </Form.Item>
          <Space size="large" wrap>
            <Form.Item name="clips_count" label="Number of Clips"><InputNumber min={1} max={50} /></Form.Item>
            <Form.Item name="ratio" label="Aspect Ratio">
              <Select style={{ width: 120 }} options={[
                { value: '9:16', label: '9:16 (TikTok)' },
                { value: '1:1', label: '1:1 (Feed)' },
                { value: '4:5', label: '4:5 (Portrait)' },
                { value: '3:4', label: '3:4 (Classic)' },
              ]} />
            </Form.Item>
            <Form.Item name="ai_provider" label="AI Provider">
              <Select style={{ width: 160 }} options={[
                { value: 'gemini', label: 'Google Gemini' },
                { value: 'anthropic', label: 'Anthropic Claude' },
                { value: 'openai', label: 'OpenAI' },
                { value: 'ollama', label: 'Ollama (Local)' },
              ]} />
            </Form.Item>
          </Space>
          <Card size="small" title="Features" style={{ marginBottom: 24 }}>
            <Space direction="vertical" size="middle">
              <Form.Item name="hook_enabled" label="Cinematic Hook" valuePropName="checked" style={{ marginBottom: 0 }}><Switch /></Form.Item>
              <Form.Item name="captions_enabled" label="AI Captions" valuePropName="checked" style={{ marginBottom: 0 }}><Switch /></Form.Item>
              <Form.Item name="bgm_enabled" label="Background Music" valuePropName="checked" style={{ marginBottom: 0 }}><Switch /></Form.Item>
              <Form.Item name="diarization_enabled" label="Speaker Detection (Podcast)" valuePropName="checked" style={{ marginBottom: 0 }}><Switch /></Form.Item>
            </Space>
          </Card>
          <Button type="primary" htmlType="submit" loading={submitting} block size="large">Create & Start</Button>
        </Form>
      </Card>
    </div>
  )
}

export default NewJob
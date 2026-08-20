import { Card, Typography, Button, Space, Tag, Rate, Empty } from 'antd'
import { CheckOutlined, CloseOutlined, DownloadOutlined } from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { jobsApi, Clip } from '../api/jobs'

function ClipReview() {
  const { id } = useParams<{ id: string }>()
  const queryClient = useQueryClient()
  const { data: clips, isLoading } = useQuery({ queryKey: ['clips', id], queryFn: () => jobsApi.clips(id!) })

  const updateClipStatus = async (clipId: string, status: 'selected' | 'rejected') => {
    try {
      const res = await fetch(`/api/clips/${clipId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status }),
      })
      if (res.ok) queryClient.invalidateQueries({ queryKey: ['clips', id] })
    } catch (e) { console.error('Failed to update clip', e) }
  }

  if (isLoading) return <Typography.Text>Loading clips...</Typography.Text>
  if (!clips || clips.length === 0) return <Empty description="No clips generated yet — check back after processing" />

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>Clip Review</Typography.Title>
        <Button type="primary" icon={<DownloadOutlined />}>Export Selected</Button>
      </div>
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        {clips.map((clip) => (
          <Card key={clip.id} size="small"
            style={{ borderColor: clip.status === 'selected' ? '#10B981' : clip.status === 'rejected' ? '#EF4444' : undefined }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <Typography.Text strong>{formatTime(clip.start_time)} — {formatTime(clip.end_time)}</Typography.Text>
                <br />
                <Rate disabled value={clip.score / 2} count={5} style={{ fontSize: 14 }} />
                {clip.ai_reasoning && (
                  <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>{clip.ai_reasoning}</Typography.Paragraph>
                )}
                {clip.output_path && (
                  <Typography.Paragraph type="secondary" style={{ marginTop: 4, marginBottom: 0 }}>
                    <a href={`file://${clip.output_path}`} target="_blank" rel="noreferrer">Open output file</a>
                  </Typography.Paragraph>
                )}
              </div>
              <Space>
                {clip.status === 'pending' && (
                  <>
                    <Button type="primary" icon={<CheckOutlined />} onClick={() => updateClipStatus(clip.id, 'selected')}>Select</Button>
                    <Button danger icon={<CloseOutlined />} onClick={() => updateClipStatus(clip.id, 'rejected')}>Reject</Button>
                  </>
                )}
                {clip.status === 'selected' && <Tag color="green">Selected</Tag>}
                {clip.status === 'rejected' && <Tag color="red">Rejected</Tag>}
              </Space>
            </div>
          </Card>
        ))}
      </Space>
    </div>
  )
}

function formatTime(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}

export default ClipReview
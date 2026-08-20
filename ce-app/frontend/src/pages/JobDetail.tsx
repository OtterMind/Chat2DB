import { useEffect, useState } from 'react'
import { Card, Typography, Button, Progress, Space, Tag, message, Descriptions } from 'antd'
import { PlayCircleOutlined, PauseCircleOutlined, ReloadOutlined, DeleteOutlined, ScissorOutlined } from '@ant-design/icons'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { jobsApi } from '../api/jobs'
import { wsClient, JobEvent } from '../api/websocket'

function JobDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [liveProgress, setLiveProgress] = useState<JobEvent & { type: 'job:progress' } | null>(null)
  const { data: job, isLoading } = useQuery({ queryKey: ['job', id], queryFn: () => jobsApi.get(id!) })

  useEffect(() => {
    wsClient.connect()
    const unsub = wsClient.onEvent((event) => {
      if (event.job_id === id) {
        if (event.type === 'job:progress') setLiveProgress(event)
        else if (event.type === 'job:done' || event.type === 'job:failed') {
          queryClient.invalidateQueries({ queryKey: ['job', id] })
          queryClient.invalidateQueries({ queryKey: ['jobs'] })
        }
      }
    })
    return unsub
  }, [id, queryClient])

  const refresh = async () => {
    if (!id) return
    queryClient.invalidateQueries({ queryKey: ['job', id] })
  }

  const handleDelete = async () => {
    if (!id) return
    await jobsApi.remove(id)
    message.success('Job deleted')
    navigate('/')
  }

  if (isLoading) return <Typography.Text>Loading...</Typography.Text>
  if (!job) return <Typography.Text>Job not found</Typography.Text>

  const progress = liveProgress?.progress ?? job.progress

  return (
    <div style={{ maxWidth: 900, margin: '0 auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>{job.name}</Typography.Title>
        <Space>
          {job.status === 'pending' && (
            <Button type="primary" icon={<PlayCircleOutlined />}
              onClick={async () => { await jobsApi.start(id!); refresh() }}>Start</Button>
          )}
          {job.status === 'processing' && (
            <Button danger icon={<PauseCircleOutlined />}
              onClick={async () => { await jobsApi.cancel(id!); refresh() }}>Cancel</Button>
          )}
          {job.status === 'failed' && (
            <Button type="primary" icon={<ReloadOutlined />}
              onClick={async () => { await jobsApi.retry(id!); await jobsApi.start(id!); refresh() }}>Retry</Button>
          )}
          <Button icon={<DeleteOutlined />} onClick={handleDelete}>Delete</Button>
          {job.status === 'done' && (
            <Button type="primary" icon={<ScissorOutlined />} onClick={() => navigate(`/jobs/${id}/clips`)}>View Clips</Button>
          )}
        </Space>
      </div>

      <Card>
        <Descriptions column={2}>
          <Descriptions.Item label="Status">
            <Tag color={job.status === 'done' ? 'green' : job.status === 'failed' ? 'red' : job.status === 'processing' ? 'blue' : 'default'}>
              {job.status}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="Source">{job.source_type} — {job.source_url}</Descriptions.Item>
        </Descriptions>
        {job.status === 'processing' && (
          <div style={{ marginTop: 24 }}>
            <Progress percent={Math.round(progress)} status="active" />
            {liveProgress?.message && <Typography.Text type="secondary">{liveProgress.message}</Typography.Text>}
          </div>
        )}
      </Card>

      {job.error && (
        <Card style={{ marginBottom: 24, borderColor: '#EF4444' }}>
          <Typography.Text type="danger">Error: {job.error}</Typography.Text>
        </Card>
      )}
    </div>
  )
}

export default JobDetail
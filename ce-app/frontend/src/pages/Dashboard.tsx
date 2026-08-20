import { useEffect, useState } from 'react'
import { Card, Col, Row, Statistic, Typography, Button, Empty } from 'antd'
import { PlusOutlined, PlayCircleOutlined, CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { jobsApi, systemApi } from '../api/jobs'
import { wsClient, JobEvent } from '../api/websocket'

function Dashboard() {
  const navigate = useNavigate()
  const [live, setLive] = useState<Record<string, JobEvent & { type: 'job:progress' }>>({})
  const { data: jobsData } = useQuery({ queryKey: ['jobs'], queryFn: () => jobsApi.list(1, 10) })
  const { data: systemInfo } = useQuery({ queryKey: ['systemInfo'], queryFn: () => systemApi.info() })

  useEffect(() => {
    wsClient.connect()
    const unsub = wsClient.onEvent((event) => {
      if (event.type === 'job:progress') setLive((prev) => ({ ...prev, [event.job_id]: event }))
    })
    return unsub
  }, [])

  const jobs = jobsData?.jobs ?? []
  const total = jobsData?.total ?? 0
  const done = jobs.filter((j) => j.status === 'done').length
  const failed = jobs.filter((j) => j.status === 'failed').length
  const processing = jobs.filter((j) => j.status === 'processing').length

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>Dashboard</Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/new')}>New Job</Button>
      </div>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="Total Jobs" value={total} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="Processing" value={processing} prefix={<PlayCircleOutlined />} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="Completed" value={done} prefix={<CheckCircleOutlined />} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="Failed" value={failed} prefix={<CloseCircleOutlined />} /></Card></Col>
      </Row>

      <Card style={{ marginTop: 24 }} title="System Status">
        <Row gutter={16}>
          <Col span={6}><Statistic title="FFmpeg" value={systemInfo?.ffmpeg_found ? '✓ Found' : '✗ Missing'} /></Col>
          <Col span={6}><Statistic title="CUDA" value={systemInfo?.cuda_available ? '✓ Available' : '✗ CPU Only'} /></Col>
          <Col span={6}><Statistic title="Free Disk" value={systemInfo?.disk_free_gb ?? 0} suffix="GB" /></Col>
          <Col span={6}><Statistic title="Memory" value={systemInfo?.memory_gb ?? 0} suffix="GB" /></Col>
        </Row>
      </Card>

      <Card style={{ marginTop: 24 }} title="Recent Jobs">
        {jobs.length === 0 ? (
          <Empty description="No jobs yet — create your first one!" />
        ) : (
          jobs.map((job) => {
            const l = live[job.id]
            const progress = l ? l.progress : job.progress
            return (
              <Card key={job.id} size="small" style={{ marginBottom: 12, cursor: 'pointer' }} onClick={() => navigate(`/jobs/${job.id}`)}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div>
                    <Typography.Text strong>{job.name}</Typography.Text><br />
                    <Typography.Text type="secondary">{job.current_stage ?? job.status} · {Math.round(progress)}%</Typography.Text>
                  </div>
                  <div>{job.status === 'processing' && l && <Typography.Text style={{ color: '#6366F1' }}>{l.stage}</Typography.Text>}</div>
                </div>
              </Card>
            )
          })
        )}
      </Card>
    </div>
  )
}

export default Dashboard
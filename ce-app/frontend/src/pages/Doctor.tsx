import { Card, Typography, Button, Tag, Descriptions, Space, Alert, Spin } from 'antd'
import { ReloadOutlined, CheckCircleOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { systemApi } from '../api/jobs'

function Doctor() {
  const { data, isLoading, refetch, isFetching } = useQuery({ queryKey: ['doctor'], queryFn: () => systemApi.doctor() })
  const diagnostics = data as any

  return (
    <div style={{ maxWidth: 900, margin: '0 auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>System Doctor</Typography.Title>
        <Button icon={<ReloadOutlined />} loading={isFetching} onClick={() => refetch()}>Re-run</Button>
      </div>
      {isLoading && <Spin size="large" />}
      {diagnostics && (
        <>
          <Card title="Health Status" style={{ marginBottom: 24 }}
            extra={<Tag color={diagnostics.healthy ? 'green' : 'red'}>{diagnostics.healthy ? 'HEALTHY' : 'NEEDS ATTENTION'}</Tag>}>
            {diagnostics.warnings?.length > 0 && (
              <Alert type="warning" message="Warnings" description={
                <ul>{diagnostics.warnings.map((w: string, i: number) => <li key={i}>{w}</li>)}</ul>
              } />
            )}
            {(!diagnostics.warnings || diagnostics.warnings.length === 0) && (
              <Space><CheckCircleOutlined style={{ color: '#10B981', fontSize: 24 }} /><Typography.Text>Everything looks great!</Typography.Text></Space>
            )}
          </Card>
          <Card title="System" style={{ marginBottom: 24 }}>
            <Descriptions column={2} size="small">
              <Descriptions.Item label="Platform">{diagnostics.system?.platform}</Descriptions.Item>
              <Descriptions.Item label="Python">{diagnostics.system?.python_version}</Descriptions.Item>
              <Descriptions.Item label="CPU Cores">{diagnostics.system?.cpu_count}</Descriptions.Item>
              <Descriptions.Item label="Memory">{diagnostics.system?.memory_gb} GB</Descriptions.Item>
              <Descriptions.Item label="Free Disk">{diagnostics.system?.disk_free_gb} GB</Descriptions.Item>
            </Descriptions>
          </Card>
          <Card title="FFmpeg">
            <Descriptions column={1} size="small">
              <Descriptions.Item label="Found">{diagnostics.ffmpeg?.found ? '✓ Yes' : '✗ No'}</Descriptions.Item>
              <Descriptions.Item label="Path">{diagnostics.ffmpeg?.path ?? 'Not found'}</Descriptions.Item>
            </Descriptions>
          </Card>
        </>
      )}
    </div>
  )
}

export default Doctor
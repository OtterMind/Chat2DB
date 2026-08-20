import { Card, Typography, Button, Table, Tag } from 'antd'
import { YoutubeOutlined, FacebookOutlined } from '@ant-design/icons'

interface UploadRecord {
  id: string; clip_id: string; platform: 'youtube' | 'facebook'; status: string
  platform_url: string | null; error: string | null; scheduled_at: string | null; published_at: string | null
}

const columns = [
  { title: 'Clip', dataIndex: 'clip_id', key: 'clip_id' },
  {
    title: 'Platform', dataIndex: 'platform', key: 'platform',
    render: (v: string) => v === 'youtube'
      ? <Tag color="red" icon={<YoutubeOutlined />}>YouTube</Tag>
      : <Tag color="blue" icon={<FacebookOutlined />}>Facebook</Tag>,
  },
  {
    title: 'Status', dataIndex: 'status', key: 'status',
    render: (v: string) => <Tag color={v === 'published' ? 'green' : v === 'failed' ? 'red' : 'blue'}>{v}</Tag>,
  },
  { title: 'URL', dataIndex: 'platform_url', key: 'platform_url', render: (v: string) => v ? <a href={v} target="_blank">{v}</a> : '-' },
]

function Uploads() {
  return (
    <div>
      <Typography.Title level={3}>Uploads</Typography.Title>
      <Card>
        <div style={{ marginBottom: 16 }}>
          <Typography.Text type="secondary">Manage your YouTube and Facebook uploads.</Typography.Text>
        </div>
        <Table<UploadRecord> columns={columns} dataSource={[]} locale={{ emptyText: 'No uploads yet' }} rowKey="id" />
        <div style={{ marginTop: 16 }}>
          <Button icon={<YoutubeOutlined />} style={{ marginRight: 12 }}>Upload to YouTube</Button>
          <Button icon={<FacebookOutlined />}>Upload to Facebook</Button>
        </div>
      </Card>
    </div>
  )
}

export default Uploads
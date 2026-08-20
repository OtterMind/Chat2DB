import { Layout as AntLayout, Menu, Typography } from 'antd'
import {
  DashboardOutlined, PlusCircleOutlined, SettingOutlined, UploadOutlined, MedicineBoxOutlined, ScissorOutlined,
} from '@ant-design/icons'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'

const { Sider, Content } = AntLayout

function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const menuItems = [
    { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
    { key: '/new', icon: <PlusCircleOutlined />, label: 'New Job' },
    { key: '/uploads', icon: <UploadOutlined />, label: 'Uploads' },
    { key: '/settings', icon: <SettingOutlined />, label: 'Settings' },
    { key: '/doctor', icon: <MedicineBoxOutlined />, label: 'Doctor' },
  ]
  const selectedKey = menuItems.find(
    (item) => item.key !== '/' && location.pathname.startsWith(item.key)
  )?.key ?? '/'

  return (
    <AntLayout style={{ minHeight: '100vh' }}>
      <Sider width={220} style={{ background: '#1E293B', borderRight: '1px solid rgba(148, 163, 184, 0.1)' }}>
        <div style={{ padding: '24px 16px', display: 'flex', alignItems: 'center', gap: 10 }}>
          <ScissorOutlined style={{ fontSize: 24, color: '#6366F1' }} />
          <Typography.Title level={4} style={{ margin: 0, color: '#F8FAFC' }}>Cutting Edge</Typography.Title>
        </div>
        <Menu theme="dark" mode="inline" selectedKeys={[selectedKey]} items={menuItems}
          onClick={({ key }) => navigate(key)} style={{ background: 'transparent', border: 'none' }} />
      </Sider>
      <Content style={{ padding: 24, background: '#0F172A', overflow: 'auto' }}>
        <Outlet />
      </Content>
    </AntLayout>
  )
}

export default AppLayout
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider, theme } from 'antd'
import { HashRouter, Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'
import NewJob from './pages/NewJob'
import JobDetail from './pages/JobDetail'
import ClipReview from './pages/ClipReview'
import Settings from './pages/Settings'
import Uploads from './pages/Uploads'
import Doctor from './pages/Doctor'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
})

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider
        theme={{
          algorithm: theme.darkAlgorithm,
          token: {
            colorPrimary: '#6366F1',
            colorBgBase: '#0F172A',
            colorBgContainer: '#1E293B',
            colorTextBase: '#F8FAFC',
            borderRadius: 8,
          },
        }}
      >
        <HashRouter>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/" element={<Dashboard />} />
              <Route path="/new" element={<NewJob />} />
              <Route path="/jobs/:id" element={<JobDetail />} />
              <Route path="/jobs/:id/clips" element={<ClipReview />} />
              <Route path="/settings" element={<Settings />} />
              <Route path="/uploads" element={<Uploads />} />
              <Route path="/doctor" element={<Doctor />} />
            </Route>
          </Routes>
        </HashRouter>
      </ConfigProvider>
    </QueryClientProvider>
  )
}

export default App
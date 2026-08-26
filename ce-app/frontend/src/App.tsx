import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect } from 'react'
import { ConfigProvider, theme } from 'antd'
import { HashRouter, Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import { RuntimeBridge } from './runtime/RuntimeBridge'
import Home from './pages/Home'
import Dashboard from './pages/Dashboard'
import Studio from './pages/Studio'
import NewJob from './pages/NewJob'
import JobDetail from './pages/JobDetail'
import ClipReview from './pages/ClipReview'
import Settings from './pages/Settings'
import Uploads from './pages/Uploads'
import Doctor from './pages/Doctor'
import StyleMatch from './pages/StyleMatch'
import { useI18n } from './i18n'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      // Data survives navigation: leaving a screen must not throw away state or
      // restart work the user already triggered.
      staleTime: 15_000,
      gcTime: 30 * 60_000,
    },
  },
})

function App() {
  const { dir, lang, antdLocale } = useI18n()

  // Keep the document in sync so CSS logical properties and native widgets
  // (scrollbars, text selection, inputs) follow the chosen language.
  useEffect(() => {
    document.documentElement.lang = lang
    document.documentElement.dir = dir
  }, [dir, lang])

  return (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider
        locale={antdLocale}
        direction={dir}
        theme={{
          algorithm: theme.darkAlgorithm,
          token: {
            colorPrimary: '#6366F1',
            colorBgBase: '#0F172A',
            colorBgContainer: '#1E293B',
            colorTextBase: '#F8FAFC',
            borderRadius: 10,
            fontFamily: "Inter, Vazirmatn, 'Segoe UI', system-ui, sans-serif",
          },
        }}
      >
        {/* Owns the WebSocket + polling for the whole app lifetime. */}
        <RuntimeBridge />
        <HashRouter>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/" element={<Home />} />
              <Route path="/dashboard" element={<Dashboard />} />
              <Route path="/studio" element={<Studio />} />
              <Route path="/new" element={<NewJob />} />
              <Route path="/jobs/:id" element={<JobDetail />} />
              <Route path="/jobs/:id/clips" element={<ClipReview />} />
              <Route path="/settings" element={<Settings />} />
              <Route path="/uploads" element={<Uploads />} />
              <Route path="/doctor" element={<Doctor />} />
              <Route path="/style" element={<StyleMatch />} />
            </Route>
          </Routes>
        </HashRouter>
      </ConfigProvider>
    </QueryClientProvider>
  )
}

export default App

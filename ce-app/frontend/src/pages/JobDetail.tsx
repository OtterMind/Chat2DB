import { useParams, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Play, Square, RotateCcw, Trash2, Scissors } from 'lucide-react'
import { message } from 'antd'
import Page, { Card, Num } from '../components/Page'
import { jobsApi } from '../api/jobs'
import { useRuntime } from '../store/runtime'
import { stageFa, statusFa } from '../lib/labels'

export default function JobDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const live = useRuntime((s) => (id ? s.tasks[id] : undefined))

  const { data: job, isLoading } = useQuery({
    queryKey: ['job', id],
    queryFn: () => jobsApi.get(id!),
    enabled: Boolean(id),
    refetchInterval: (q) => (q.state.data?.status === 'processing' ? 3000 : false),
  })

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['job', id] })
    queryClient.invalidateQueries({ queryKey: ['jobs'] })
  }

  if (isLoading) return <Page title="پروژه"><div className="ce-empty">در حال بارگذاری…</div></Page>
  if (!job) return <Page title="پروژه" back><div className="ce-empty">این پروژه پیدا نشد.</div></Page>

  const progress = live?.progress ?? job.progress
  const stage = stageFa(live?.stage ?? job.current_stage)

  return (
    <Page
      title={job.name}
      subtitle={statusFa(job.status)}
      back
      actions={
        <div className="ce-actions">
          {job.status === 'pending' && (
            <button className="ce-btn ce-btn--sm" onClick={async () => { await jobsApi.start(id!); refresh() }}>
              <Play size={15} /> شروع
            </button>
          )}
          {job.status === 'processing' && (
            <button className="ce-btn ce-btn--sm ce-btn--danger" onClick={async () => { await jobsApi.cancel(id!); refresh() }}>
              <Square size={15} /> توقف
            </button>
          )}
          {job.status === 'failed' && (
            <button className="ce-btn ce-btn--sm" onClick={async () => { await jobsApi.retry(id!); await jobsApi.start(id!); refresh() }}>
              <RotateCcw size={15} /> تلاش دوباره
            </button>
          )}
          {job.status === 'done' && (
            <button className="ce-btn ce-btn--sm" onClick={() => navigate(`/jobs/${id}/clips`)}>
              <Scissors size={15} /> کلیپ‌ها
            </button>
          )}
          <button
            className="ce-btn ce-btn--ghost ce-btn--sm"
            onClick={async () => { await jobsApi.remove(id!); message.success('پروژه حذف شد'); navigate('/dashboard') }}
          >
            <Trash2 size={15} /> حذف
          </button>
        </div>
      }
    >
      {job.status === 'processing' && (
        <Card title="پیشرفت">
          <div className="ce-jobprogress">
            <div className="ce-jobprogress__row">
              <span>{stage ?? 'در حال پردازش'}</span>
              <Num>{Math.round(progress)}%</Num>
            </div>
            <span className="ce-progress">
              <span className="ce-progress__bar" style={{ width: `${Math.max(2, progress)}%`, background: 'linear-gradient(90deg,#6366F1,#8B5CF6)' }} />
            </span>
          </div>
        </Card>
      )}

      <Card title="مشخصات">
        <div className="ce-kv"><span>وضعیت</span><strong>{statusFa(job.status)}</strong></div>
        <div className="ce-kv"><span>نوع منبع</span><strong>{job.source_type}</strong></div>
        <div className="ce-kv"><span>منبع</span><strong className="ce-kv__wrap"><Num>{job.source_url ?? '—'}</Num></strong></div>
        <div className="ce-kv"><span>ساخته شده</span><strong><Num>{new Date(job.created_at).toLocaleString('fa-IR')}</Num></strong></div>
      </Card>

      {job.error && (
        <Card title="خطا" tone="danger">
          <p className="ce-error" dir="auto">{job.error}</p>
        </Card>
      )}
    </Page>
  )
}

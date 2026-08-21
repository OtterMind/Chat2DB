import { useParams, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Play, Square, RotateCcw, Trash2, Scissors } from 'lucide-react'
import { message } from 'antd'
import Page, { Card, Num } from '../components/Page'
import { jobsApi } from '../api/jobs'
import { useRuntime } from '../store/runtime'
import { stageLabel, statusLabel } from '../lib/labels'
import { useI18n } from '../i18n'

export default function JobDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { t, lang } = useI18n()
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

  if (isLoading)
    return (
      <Page title={t('Project', 'پروژه')}>
        <div className="ce-empty">{t('Loading…', 'در حال بارگذاری…')}</div>
      </Page>
    )
  if (!job)
    return (
      <Page title={t('Project', 'پروژه')} back>
        <div className="ce-empty">{t('This project was not found.', 'این پروژه پیدا نشد.')}</div>
      </Page>
    )

  const progress = live?.progress ?? job.progress
  const stage = stageLabel(live?.stage ?? job.current_stage, lang)

  return (
    <Page
      title={job.name}
      subtitle={statusLabel(job.status, lang)}
      back
      actions={
        <div className="ce-actions">
          {job.status === 'pending' && (
            <button className="ce-btn ce-btn--sm" onClick={async () => { await jobsApi.start(id!); refresh() }}>
              <Play size={15} /> {t('Start', 'شروع')}
            </button>
          )}
          {job.status === 'processing' && (
            <button className="ce-btn ce-btn--sm ce-btn--danger" onClick={async () => { await jobsApi.cancel(id!); refresh() }}>
              <Square size={15} /> {t('Stop', 'توقف')}
            </button>
          )}
          {job.status === 'failed' && (
            <button className="ce-btn ce-btn--sm" onClick={async () => { await jobsApi.retry(id!); await jobsApi.start(id!); refresh() }}>
              <RotateCcw size={15} /> {t('Retry', 'تلاش دوباره')}
            </button>
          )}
          {job.status === 'done' && (
            <button className="ce-btn ce-btn--sm" onClick={() => navigate(`/jobs/${id}/clips`)}>
              <Scissors size={15} /> {t('Clips', 'کلیپ‌ها')}
            </button>
          )}
          <button
            className="ce-btn ce-btn--ghost ce-btn--sm"
            onClick={async () => { await jobsApi.remove(id!); message.success(t('Project deleted', 'پروژه حذف شد')); navigate('/dashboard') }}
          >
            <Trash2 size={15} /> {t('Delete', 'حذف')}
          </button>
        </div>
      }
    >
      {job.status === 'processing' && (
        <Card title={t('Progress', 'پیشرفت')}>
          <div className="ce-jobprogress">
            <div className="ce-jobprogress__row">
              <span>{stage ?? t('Processing', 'در حال پردازش')}</span>
              <Num>{Math.round(progress)}%</Num>
            </div>
            <span className="ce-progress">
              <span className="ce-progress__bar" style={{ width: `${Math.max(2, progress)}%`, background: 'linear-gradient(90deg,#6366F1,#8B5CF6)' }} />
            </span>
          </div>
        </Card>
      )}

      <Card title={t('Details', 'مشخصات')}>
        <div className="ce-kv"><span>{t('Status', 'وضعیت')}</span><strong>{statusLabel(job.status, lang)}</strong></div>
        <div className="ce-kv"><span>{t('Source type', 'نوع منبع')}</span><strong>{job.source_type}</strong></div>
        <div className="ce-kv"><span>{t('Source', 'منبع')}</span><strong className="ce-kv__wrap"><Num>{job.source_url ?? '—'}</Num></strong></div>
        <div className="ce-kv">
          <span>{t('Created', 'ساخته شده')}</span>
          <strong><Num>{new Date(job.created_at).toLocaleString(lang === 'fa' ? 'fa-IR' : 'en-GB')}</Num></strong>
        </div>
      </Card>

      {job.error && (
        <Card title={t('Error', 'خطا')} tone="danger">
          <p className="ce-error" dir="auto">{job.error}</p>
        </Card>
      )}
    </Page>
  )
}

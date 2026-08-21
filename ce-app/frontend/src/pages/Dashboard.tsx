import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import Page, { Card, Num, Stat } from '../components/Page'
import { jobsApi, systemApi } from '../api/jobs'
import { useRuntime } from '../store/runtime'
import { stageLabel, statusLabel } from '../lib/labels'
import { useI18n } from '../i18n'

export default function Dashboard() {
  const navigate = useNavigate()
  const { t, lang } = useI18n()
  const tasks = useRuntime((s) => s.tasks)

  const { data: jobsData } = useQuery({ queryKey: ['jobs'], queryFn: () => jobsApi.list(1, 20) })
  const { data: info } = useQuery({ queryKey: ['systemInfo'], queryFn: () => systemApi.info(), staleTime: 60_000 })

  const jobs = jobsData?.jobs ?? []
  const count = (s: string) => jobs.filter((j) => j.status === s).length

  return (
    <Page
      title={t('Projects', 'پروژه‌ها')}
      subtitle={t('Everything you have created and its live status', 'همه‌ی کارهای ساخته‌شده و وضعیت لحظه‌ای آن‌ها')}
      actions={
        <button className="ce-btn ce-btn--sm" onClick={() => navigate('/new')}>
          <Plus size={16} /> {t('New project', 'پروژه جدید')}
        </button>
      }
    >
      <div className="ce-stats">
        <Stat label={t('Total projects', 'کل پروژه‌ها')} value={<Num>{jobsData?.total ?? 0}</Num>} />
        <Stat label={t('Processing', 'در حال پردازش')} value={<Num>{count('processing')}</Num>} />
        <Stat label={t('Ready', 'آماده')} value={<Num>{count('done')}</Num>} />
        <Stat label={t('Failed', 'ناموفق')} value={<Num>{count('failed')}</Num>} />
      </div>

      <Card title={t('System status', 'وضعیت سیستم')}>
        <div className="ce-stats ce-stats--compact">
          <Stat label="FFmpeg" value={info?.ffmpeg_found ? t('Ready', 'آماده') : t('Not found', 'یافت نشد')} />
          <Stat label={t('GPU', 'پردازنده گرافیکی')} value={info?.cuda_available ? t('Enabled', 'فعال') : t('CPU only', 'فقط CPU')} />
          <Stat label={t('Free space', 'فضای آزاد')} value={<><Num>{info?.disk_free_gb ?? '—'}</Num> {t('GB', 'گیگابایت')}</>} />
          <Stat label={t('Memory', 'حافظه')} value={<><Num>{info?.memory_gb ?? '—'}</Num> {t('GB', 'گیگابایت')}</>} />
        </div>
      </Card>

      <Card title={t('All projects', 'فهرست پروژه‌ها')}>
        {jobs.length === 0 ? (
          <div className="ce-empty">{t('No projects yet.', 'هنوز پروژه‌ای نساخته‌ای.')}</div>
        ) : (
          <div className="ce-joblist">
            {jobs.map((job) => {
              const live = tasks[job.id]
              const progress = live?.progress ?? job.progress
              const stage = stageLabel(live?.stage ?? job.current_stage, lang)
              return (
                <button key={job.id} className="ce-jobcard" onClick={() => navigate(`/jobs/${job.id}`)}>
                  <span className={`ce-dot ce-dot--${job.status}`} />
                  <span className="ce-jobcard__name">{job.name}</span>
                  <span className="ce-jobcard__meta">
                    {job.status === 'processing' ? (
                      <>
                        {stage ?? t('Processing', 'در حال پردازش')} · <Num>{Math.round(progress)}%</Num>
                      </>
                    ) : (
                      statusLabel(job.status, lang)
                    )}
                  </span>
                </button>
              )
            })}
          </div>
        )}
      </Card>
    </Page>
  )
}

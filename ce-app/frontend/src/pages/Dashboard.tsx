import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import Page, { Card, Num, Stat } from '../components/Page'
import { jobsApi, systemApi } from '../api/jobs'
import { useRuntime } from '../store/runtime'
import { stageFa, statusFa } from '../lib/labels'

export default function Dashboard() {
  const navigate = useNavigate()
  const tasks = useRuntime((s) => s.tasks)

  const { data: jobsData } = useQuery({ queryKey: ['jobs'], queryFn: () => jobsApi.list(1, 20) })
  const { data: info } = useQuery({ queryKey: ['systemInfo'], queryFn: () => systemApi.info(), staleTime: 60_000 })

  const jobs = jobsData?.jobs ?? []
  const count = (s: string) => jobs.filter((j) => j.status === s).length

  return (
    <Page
      title="پروژه‌ها"
      subtitle="همه‌ی کارهای ساخته‌شده و وضعیت لحظه‌ای آن‌ها"
      actions={
        <button className="ce-btn ce-btn--sm" onClick={() => navigate('/new')}>
          <Plus size={16} /> پروژه جدید
        </button>
      }
    >
      <div className="ce-stats">
        <Stat label="کل پروژه‌ها" value={<Num>{jobsData?.total ?? 0}</Num>} />
        <Stat label="در حال پردازش" value={<Num>{count('processing')}</Num>} />
        <Stat label="آماده" value={<Num>{count('done')}</Num>} />
        <Stat label="ناموفق" value={<Num>{count('failed')}</Num>} />
      </div>

      <Card title="وضعیت سیستم">
        <div className="ce-stats ce-stats--compact">
          <Stat label="FFmpeg" value={info?.ffmpeg_found ? 'آماده' : 'یافت نشد'} />
          <Stat label="پردازنده گرافیکی" value={info?.cuda_available ? 'فعال' : 'فقط CPU'} />
          <Stat label="فضای آزاد" value={<><Num>{info?.disk_free_gb ?? '—'}</Num> گیگابایت</>} />
          <Stat label="حافظه" value={<><Num>{info?.memory_gb ?? '—'}</Num> گیگابایت</>} />
        </div>
      </Card>

      <Card title="فهرست پروژه‌ها">
        {jobs.length === 0 ? (
          <div className="ce-empty">هنوز پروژه‌ای نساخته‌ای.</div>
        ) : (
          <div className="ce-joblist">
            {jobs.map((job) => {
              const live = tasks[job.id]
              const progress = live?.progress ?? job.progress
              const stage = stageFa(live?.stage ?? job.current_stage)
              return (
                <button key={job.id} className="ce-jobcard" onClick={() => navigate(`/jobs/${job.id}`)}>
                  <span className={`ce-dot ce-dot--${job.status}`} />
                  <span className="ce-jobcard__name">{job.name}</span>
                  <span className="ce-jobcard__meta">
                    {job.status === 'processing' ? (
                      <>
                        {stage ?? 'در حال پردازش'} · <Num>{Math.round(progress)}%</Num>
                      </>
                    ) : (
                      statusFa(job.status)
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

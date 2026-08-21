import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Plus, Zap, TrendingUp } from 'lucide-react'
import { FEATURES, GROUP_TITLES, type FeatureTile } from '../features/catalog'
import { jobsApi, systemApi } from '../api/jobs'
import { useRuntime, selectActiveTasks } from '../store/runtime'
import RunningStrip from '../components/RunningStrip'

function Tile({ tile, onOpen }: { tile: FeatureTile; onOpen: (t: FeatureTile) => void }) {
  return (
    <button className="ce-tile" onClick={() => onOpen(tile)} title={tile.hint}>
      <span className="ce-tile__icon" style={{ background: tile.gradient }}>
        {tile.icon}
        {tile.badge && <span className={`ce-tile__badge ce-tile__badge--${tile.badge === 'به‌زودی' ? 'soon' : 'new'}`}>{tile.badge}</span>}
      </span>
      <span className="ce-tile__label">{tile.label}</span>
    </button>
  )
}

export default function Home() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const activeTasks = useRuntime(selectActiveTasks)

  const { data: jobsData } = useQuery({
    queryKey: ['jobs'],
    queryFn: () => jobsApi.list(1, 6),
    staleTime: 10_000,
  })
  const { data: info } = useQuery({
    queryKey: ['systemInfo'],
    queryFn: () => systemApi.info(),
    staleTime: 60_000,
  })

  const groups = useMemo(() => {
    const filtered = query.trim()
      ? FEATURES.filter((f) => f.label.includes(query.trim()) || f.hint.includes(query.trim()))
      : FEATURES
    const order: FeatureTile['group'][] = ['core', 'ai', 'polish', 'publish', 'system']
    return order
      .map((g) => ({ group: g, items: filtered.filter((f) => f.group === g) }))
      .filter((g) => g.items.length > 0)
  }, [query])

  const openTile = (tile: FeatureTile) => navigate(tile.route)
  const jobs = jobsData?.jobs ?? []

  return (
    <div className="ce-home">
      <div className="ce-searchbar">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="جست‌وجو در امکانات Cutting Edge"
          aria-label="جست‌وجو"
        />
      </div>

      {/* Primary call to action, styled like the promo banner of a super-app */}
      <section className="ce-banner ce-banner--primary" onClick={() => navigate('/new')}>
        <div className="ce-banner__text">
          <h2>ویدیوی بلندت را به کلیپ وایرال تبدیل کن</h2>
          <p>لینک یوتیوب بده یا فایل بگذار — بقیه‌اش با هوش مصنوعی</p>
        </div>
        <span className="ce-banner__cta">
          <Plus size={16} /> پروژه جدید
        </span>
      </section>

      {activeTasks.length > 0 && <RunningStrip />}

      {groups.map(({ group, items }) => (
        <section key={group} className="ce-group">
          <div className="ce-group__head">
            <h3>{GROUP_TITLES[group]}</h3>
          </div>
          <div className="ce-grid">
            {items.map((tile) => (
              <Tile key={tile.id} tile={tile} onOpen={openTile} />
            ))}
          </div>
        </section>
      ))}

      <section className="ce-banner ce-banner--secondary">
        <div className="ce-banner__text">
          <h2>
            <Zap size={18} style={{ verticalAlign: '-3px' }} /> کاملاً رایگان و متن‌باز
          </h2>
          <p>بدون اشتراک، بدون واترمارک، پردازش روی سیستم خودت</p>
        </div>
      </section>

      <section className="ce-group">
        <div className="ce-group__head">
          <h3>پروژه‌های اخیر</h3>
          <button className="ce-link" onClick={() => navigate('/dashboard')}>
            همه <ArrowLeft size={14} />
          </button>
        </div>
        {jobs.length === 0 ? (
          <div className="ce-empty">هنوز پروژه‌ای نساخته‌ای. از «پروژه جدید» شروع کن.</div>
        ) : (
          <div className="ce-joblist">
            {jobs.map((job) => (
              <button key={job.id} className="ce-jobcard" onClick={() => navigate(`/jobs/${job.id}`)}>
                <span className={`ce-dot ce-dot--${job.status}`} />
                <span className="ce-jobcard__name">{job.name}</span>
                <span className="ce-jobcard__meta">
                  {job.current_stage ?? job.status} · {Math.round(job.progress)}٪
                </span>
              </button>
            ))}
          </div>
        )}
      </section>

      <section className="ce-status">
        <TrendingUp size={14} />
        <span>FFmpeg: {info?.ffmpeg_found ? 'آماده' : 'یافت نشد'}</span>
        <span>پردازنده گرافیکی: {info?.cuda_available ? 'فعال' : 'CPU'}</span>
        <span>فضای آزاد: {info?.disk_free_gb ?? '—'} گیگابایت</span>
      </section>
    </div>
  )
}
